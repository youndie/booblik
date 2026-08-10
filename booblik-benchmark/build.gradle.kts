import kotlinx.benchmark.gradle.BenchmarkConfiguration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.benchmark)
}

dependencies {
    implementation(project(":booblik-core"))
    implementation(libs.benchmark.runtime)
    implementation(libs.hdrhistogram)
}

// The benchmark runs under the same footprint as everything else (see `brokerJvmArgs` in the root
// build). JMH hands the host VM's arguments to the process it forks, so setting them here reaches
// the JVM that is actually measured — and JMH prints them back as "# VM options:" in its header,
// which is where to check rather than assume.
@Suppress("UNCHECKED_CAST")
val brokerJvmArgs = rootProject.extra["brokerJvmArgs"] as List<String>

tasks.withType<JavaExec>().configureEach {
    jvmArgs(brokerJvmArgs)
}

// JMH generates a subclass of every @State class, so those classes must not be final. Kotlin makes
// them final by default; this is the single line that keeps the benchmarks from failing at
// generation time with a message about a final class that reads like a compiler bug.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

// The benchmarks live in `main`, not in a source set of their own, and that is deliberate: `main`
// is compiled by `assemble`, so a benchmark that stops compiling after a refactor breaks the build
// on the spot. A benchmark that is not compiled by the ordinary build quietly rots and is
// discovered months later, when the number it used to produce is needed.
benchmark {
    targets {
        register("main")
    }
    configurations {
        // Full run: the number you are allowed to write down.
        named("main") {
            common()
            warmups = 5
            iterations = 10
        }
        // `./gradlew :booblik-benchmark:mainQuickBenchmark` — an order of magnitude, in a minute.
        // Not for the record: too few iterations to say anything about the second digit.
        register("quick") {
            common()
            warmups = 2
            iterations = 3
        }
    }
}

fun BenchmarkConfiguration.common() {
    // Throughput, because the question this project keeps asking is "how many records per second".
    mode = "thrpt"
    outputTimeUnit = "s"
    iterationTime = 2
    iterationTimeUnit = "s"
    reportFormat = "text"
}
