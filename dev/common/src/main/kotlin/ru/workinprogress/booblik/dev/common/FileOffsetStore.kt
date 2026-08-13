package ru.workinprogress.booblik.dev.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.OffsetStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The position of one consumer, in a file on its own volume.
 *
 * `OffsetStore` ships declared and not implemented, and that is deliberate: where a position lives
 * — a file, a row, the same transaction as the work — is a decision about a system the library
 * knows nothing about. This is what the simplest of those decisions looks like.
 *
 * Shared by the consumer and by the relay, which is why it is here rather than in either: both need
 * a position that outlives the process, and neither is entitled to own the other's copy.
 *
 * Written through a temporary file and an atomic move. A position saved half-way is worse than no
 * position at all: `"12"` truncated to `"1"` parses fine and silently replays eleven records, which
 * is the kind of bug that gets blamed on the broker.
 */
class FileOffsetStore(
    private val directory: Path,
) : OffsetStore {
    init {
        Files.createDirectories(directory)
    }

    override suspend fun load(
        topic: TopicName,
        partition: PartitionId,
    ): Offset? =
        withContext(Dispatchers.IO) {
            val file = fileFor(topic, partition)
            if (!Files.exists(file)) return@withContext null
            Files.readString(file).trim().toLongOrNull()?.let(::Offset)
        }

    override suspend fun save(
        topic: TopicName,
        partition: PartitionId,
        offset: Offset,
    ) {
        withContext(Dispatchers.IO) {
            val file = fileFor(topic, partition)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temporary, offset.value.toString())
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun fileFor(
        topic: TopicName,
        partition: PartitionId,
    ): Path = directory.resolve("${topic.value}-${partition.value}.offset")
}
