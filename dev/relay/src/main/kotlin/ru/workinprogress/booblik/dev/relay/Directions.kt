package ru.workinprogress.booblik.dev.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.dev.common.FileOffsetStore
import ru.workinprogress.booblik.net.client.BooblikConnection
import ru.workinprogress.booblik.net.client.BooblikSubscriber
import ru.workinprogress.booblik.net.client.Producer
import ru.workinprogress.booblik.net.client.StartPosition
import ru.workinprogress.booblik.net.client.checkpointing
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Properties
import kotlin.io.path.Path

/**
 * Kafka to booblik.
 *
 * The position belongs to Kafka here, named by a consumer group, so there is nothing of our own to
 * store. Auto-commit is **off**: the ordering of the last two statements in the loop is the entire
 * delivery guarantee, and auto-commit would move the position on a timer that knows nothing about
 * whether booblik took the batch.
 *
 * The Kafka key is passed to `TopicHandle.send`, so it still chooses the booblik partition and
 * per-key ordering survives the crossing. The key itself does not: booblik's wire has no field for
 * it.
 */
suspend fun kafkaToBooblik(
    config: RelayConfig,
    stats: Stats,
) {
    val scope = CoroutineScope(SupervisorJob())
    val properties =
        Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap)
            put(ConsumerConfig.GROUP_ID_CONFIG, config.kafkaGroup)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            // A relay pointed at an existing topic is usually meant to carry what is already there.
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }

    BooblikConnection(InetSocketAddress(config.brokerHost, config.brokerPort), scope).use { connection ->
        Producer(connection, scope).use { producer ->
            val handle = producer.topic(TopicName(config.booblikTopic))
            KafkaConsumer<ByteArray, ByteArray>(properties).use { consumer ->
                consumer.subscribe(listOf(config.kafkaTopic))
                while (true) {
                    // KafkaConsumer is blocking and not thread-safe; it stays on one IO thread and
                    // is never touched from anywhere else.
                    val records = withContext(Dispatchers.IO) { consumer.poll(Duration.ofMillis(500)) }
                    if (records.isEmpty) continue

                    val acknowledgements =
                        records.map { record -> handle.send(record.value(), key = record.key()) }
                    val offsets = acknowledgements.map { it.await() }

                    // Committed only once every record of the batch is in booblik. A crash between
                    // these two lines repeats the batch on restart; committing first would drop it.
                    withContext(Dispatchers.IO) { consumer.commitSync() }
                    stats.observe(records.count(), offsets.lastOrNull()?.value, records.lastOrNull()?.value())
                }
            }
        }
    }
}

/**
 * booblik to Kafka.
 *
 * Nothing on the broker side remembers a reader, so the position is ours to keep — the same
 * `FileOffsetStore` the sample's consumer uses, on the relay's own volume.
 *
 * `flush()` before the checkpoint, and that order is the guarantee: `checkpointing` saves after the
 * collector returns, so returning before Kafka has the records would move the position past records
 * that were only queued.
 */
suspend fun booblikToKafka(
    config: RelayConfig,
    stats: Stats,
) {
    val store = FileOffsetStore(Path(config.stateDir))
    val topic = TopicName(config.booblikTopic)
    val properties =
        Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            // The relay promises at-least-once, and `acks=all` is what makes the promise true on
            // the Kafka side rather than only on ours.
            put(ProducerConfig.ACKS_CONFIG, "all")
        }

    KafkaProducer<ByteArray, ByteArray>(properties).use { kafka ->
        BooblikSubscriber(InetSocketAddress(config.brokerHost, config.brokerPort)).use { subscriber ->
            // Every partition, not one: a relay is for the topic. `checkpointing` keeps a position
            // per partition, which is why it can be handed a merged flow at all.
            val saved = store.load(topic, ru.workinprogress.booblik.PartitionId(0))
            subscriber
                .follow(topic, saved?.let { StartPosition.At(it) } ?: StartPosition.Earliest)
                .checkpointing(store)
                .collect { batch ->
                    batch.records.forEach { record ->
                        // No key: booblik never stored one, and inventing a key here would put
                        // records into Kafka partitions by a rule nobody chose.
                        kafka.send(ProducerRecord(config.kafkaTopic, record))
                    }
                    withContext(Dispatchers.IO) { kafka.flush() }
                    stats.observe(batch.records.size, batch.nextOffset.value, batch.records.lastOrNull())
                }
        }
    }
}
