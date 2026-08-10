package ru.workinprogress.booblik.benchmark

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a benchmark or a probe is allowed to put the files it measures.
 *
 * Every measuring entry point used to call `Files.createTempDirectory(prefix)`, which lands in
 * `java.io.tmpdir` — and on Ubuntu 26.04 that is **tmpfs**. Measuring storage on tmpfs measures
 * memory: `fsync` there has nothing to reach and returns in the time a syscall takes. The M-24
 * probe duly reported a 0.01 ms `fsync` after dirtying 32 MiB, printed its verdict paragraph
 * about `msync` in the usual confident tone, and none of it was about a disk (M-46).
 *
 * That is the failure this class exists to make impossible. It is the same shape as the guard in
 * `ci/wsl-run.sh` — a tree synced into `/mnt/c` builds and runs perfectly while measuring 9p over
 * NTFS — and the lesson is the same one: a stand that measures the wrong medium does not look
 * broken, it looks fast.
 *
 * Two things follow, and both are deliberate:
 *
 * * the default is **not** the system temp directory but `build/measurements`, which sits wherever
 *   the checkout does — on a real filesystem, because that is where source trees live;
 * * a volatile filesystem is a **refusal**, not a warning. A warning printed above a number gets
 *   read after the number has already been believed.
 */
object MeasurementDir {
    /** Override for pointing a run at a particular device: `-Dbooblik.bench.dir=/mnt/nvme1/bench`. */
    const val PROPERTY = "booblik.bench.dir"

    /** Filesystems that never reach a device, and on which every durability number is fiction. */
    private val VOLATILE = setOf("tmpfs", "ramfs")

    /**
     * Creates a directory for one measurement run, refusing to return one that cannot be measured.
     *
     * @throws IllegalStateException if the directory lives on a filesystem that only exists in RAM.
     */
    fun create(prefix: String): Path {
        val base = System.getProperty(PROPERTY)?.let(Path::of) ?: Path.of("build", "measurements")
        Files.createDirectories(base)
        val store = Files.getFileStore(base)
        check(store.type() !in VOLATILE) {
            "measurement directory ${base.toAbsolutePath()} is on ${store.type()}, which never reaches a " +
                "device: fsync is a no-op there and every number this run produces would describe memory. " +
                "Point -D$PROPERTY at a directory on real storage."
        }
        return Files.createTempDirectory(base, prefix)
    }

    /**
     * How the medium under [path] should be named in a report header.
     *
     * Printed by every probe next to the JVM version for the same reason the JVM version is
     * printed: a number whose medium is not written down cannot be compared with anything later,
     * and the project's own rule is that runs from different hosts are not comparable.
     */
    fun describe(path: Path): String =
        runCatching {
            val store = Files.getFileStore(path)
            "${store.type()} on ${store.name()}"
        }.getOrDefault("unknown filesystem")
}
