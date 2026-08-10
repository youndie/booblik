package ru.workinprogress.booblik.benchmark

import java.lang.management.ManagementFactory

/**
 * Fails the run if the JVM being measured is not the one booblik is designed for.
 *
 * The flags are set in Gradle (`brokerJvmArgs` in the root build) and reach the measured process
 * because JMH hands the host VM's arguments to the process it forks. "Because JMH does X" is
 * exactly the kind of assumption that stops being true after a version bump, and the failure mode
 * is silent: the benchmark keeps running, produces plausible numbers, and they describe a JVM
 * nobody intends to deploy. So it is checked rather than trusted.
 *
 * Every trial prints the footprint it ran under, for the same reason a throughput number has to
 * name its flush policy — a number that does not carry its conditions cannot be compared to
 * anything.
 */
internal object RuntimeFootprint {
    /**
     * Flags whose absence would change the numbers enough to invalidate them. Deliberately not the
     * whole list: this is a guard against the arguments not arriving at all, not a second copy of
     * the build configuration that would have to be kept in sync.
     */
    private val REQUIRED = listOf("-Xmx64M", "-XX:+UseSerialGC")

    private var reported = false

    fun verify() {
        val actual = ManagementFactory.getRuntimeMXBean().inputArguments
        val missing = REQUIRED.filterNot(actual::contains)
        require(missing.isEmpty()) {
            "benchmark JVM is not running under the broker footprint: missing $missing. " +
                "Actual arguments: $actual. See brokerJvmArgs in the root build.gradle.kts."
        }
        if (!reported) {
            reported = true
            println("# booblik runtime footprint: ${actual.joinToString(" ")}")
        }
    }
}
