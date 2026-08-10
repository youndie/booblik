package ru.workinprogress.booblik.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.workinprogress.booblik.net.BooblikServer
import ru.workinprogress.booblik.net.Broker
import ru.workinprogress.booblik.net.BrokerConfig
import ru.workinprogress.booblik.net.Metrics
import ru.workinprogress.booblik.net.ServerConfig
import java.util.concurrent.CountDownLatch
import kotlin.io.path.Path

/**
 * Runs a broker.
 *
 * ```
 * ./gradlew :booblik-app:run --args="broker.properties"
 * ```
 *
 * Every setting can also come from the environment, so a container needs no file at all — see
 * [BooblikConfig].
 *
 * ## What this deliberately does not do
 *
 * No daemonising, no PID file, no log framework. A broker is started by whatever supervises it —
 * systemd, a container runtime, a test — and each of those already has opinions about all three.
 * Output goes to stdout because that is what every one of them reads.
 */
fun main(args: Array<String>) {
    val config = BooblikConfig.load(args.firstOrNull()?.let(::Path))
    println("booblik starting")
    println(config.describe().prependIndent("  "))
    // Профиль, с которым процесс реально живёт, а не тот, который предполагается.
    //
    // Он зашит в стартовый скрипт дистрибутива, но `JAVA_OPTS` его перебивает, и в контейнере это
    // делается одной строкой в чужом `Dockerfile`. Молчаливая подмена означает, что поставляется
    // не то, что измерено (риск 7), а отличить одно от другого снаружи было нечем. Теперь есть:
    // строка печатается всегда и попадает в логи контейнера.
    println("  jvm: " + jvmArguments().joinToString(" ").ifEmpty { "(без аргументов — профиль не доехал)" })

    val broker =
        Broker.open(
            dir = config.dataDir,
            partitions = config.topics,
            config =
                BrokerConfig(
                    segmentMode = config.segmentMode,
                    segmentCapacity = config.segmentCapacity,
                    indexIntervalBytes = config.indexIntervalBytes,
                    flushPolicy = config.flushPolicy,
                    retainedBytesPerPartition = config.retentionBytes,
                ),
        )

    val metrics = Metrics()
    val server =
        BooblikServer(
            broker.registry,
            ServerConfig(
                port = config.port,
                bindAddress = config.bindAddress,
                transport = config.transport,
                fetchMode = config.fetchMode,
            ),
            metrics,
        )

    val address = server.start()
    println("booblik listening on $address")
    for (key in broker.partitions) {
        val handle = broker.handle(key.topic, key.partition)!!
        println(
            "  ${key.topic.value}-${key.partition.value}: " +
                "offsets ${handle.log.logStartOffset}..${handle.log.nextOffset}, " +
                "${handle.log.segmentCount} segment(s), ${handle.log.sizeInBytes} bytes",
        )
    }

    val background = CoroutineScope(SupervisorJob())
    background.launch { reportMetrics(metrics, broker, config.metricsIntervalMillis) }
    background.launch { applyRetention(broker, config) }

    // Held open until the JVM is asked to stop. A broker has nothing to do on the main thread, and
    // spinning or sleeping in a loop here would only obscure that.
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("booblik stopping")
            server.close()
            background.cancel()
            // The broker closes last and closes its writers first, so a batch that was accepted
            // reaches the disk before the log under it goes away.
            broker.close()
            println("booblik stopped")
            stopped.countDown()
        },
    )
    stopped.await()
}

/**
 * Prints a rate line every interval.
 *
 * Rates rather than raw counters, because a counter printed every ten seconds is something the
 * reader has to differentiate by hand at the exact moment they are trying to understand an
 * incident. The counters are still there in the snapshot for anyone who wants them.
 */
private suspend fun reportMetrics(
    metrics: Metrics,
    broker: Broker,
    intervalMillis: Long,
) {
    if (intervalMillis <= 0) return
    var previous = metrics.snapshot(broker)
    while (true) {
        delay(intervalMillis)
        val current = metrics.snapshot(broker)
        println("booblik: " + current.since(previous, intervalMillis))
        previous = current
    }
}

/**
 * Applies retention on a timer.
 *
 * The broker itself has no clock — [Broker.applyRetention] does what it is told, when it is told —
 * so that tests can advance time by calling it rather than by waiting. This is the only place that
 * decides *when*.
 */
private suspend fun applyRetention(
    broker: Broker,
    config: BooblikConfig,
) {
    if (config.retentionBytes == null && config.retentionMillis == null) return
    while (true) {
        delay(config.retentionCheckMillis)
        val removed = broker.applyRetention(config.retentionMillis, System.currentTimeMillis())
        if (removed > 0) println("booblik: retention removed $removed segment(s)")
    }
}

/**
 * Аргументы, с которыми запущена эта JVM.
 *
 * Отфильтрованы до того, что задаёт профиль: `-D…` и пути тут только зашумили бы строку, ради
 * которой всё и печатается.
 */
private fun jvmArguments(): List<String> =
    java.lang.management.ManagementFactory
        .getRuntimeMXBean()
        .inputArguments
        .filter { it.startsWith("-X") }
