import kotlinx.benchmark.gradle.BenchmarkConfiguration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.benchmark)
}

dependencies {
    implementation(project(":booblik-core"))
    implementation(project(":booblik-net"))
    // Declared here rather than inherited: `:booblik-core` keeps coroutines as `implementation`, so
    // it does not leak onto consumers' compile classpaths — which is right for a library and means
    // anything driving the writer has to ask for them itself.
    implementation(libs.coroutines.core)
    implementation(libs.benchmark.runtime)
    implementation(libs.hdrhistogram)
}

// The benchmark runs under the same footprint as everything else (see `brokerJvmArgs` in the root
// build). JMH hands the host VM's arguments to the process it forks, so setting them here reaches
// the JVM that is actually measured — and JMH prints them back as "# VM options:" in its header,
// which is where to check rather than assume.
@Suppress("UNCHECKED_CAST")
val brokerJvmArgs = rootProject.extra["brokerJvmArgs"] as List<String>

// Measured files go next to the build output — that is, on whatever filesystem the checkout lives
// on — and never in `java.io.tmpdir`, which on Ubuntu 26.04 is tmpfs. `MeasurementDir` refuses to
// run on a volatile filesystem; this only makes the location explicit and absolute, so a JMH fork
// started from another working directory still lands on the disk that was meant (M-46).
val measurementDir =
    layout.buildDirectory
        .dir("measurements")
        .get()
        .asFile.absolutePath

tasks.withType<JavaExec>().configureEach {
    jvmArgs(brokerJvmArgs)
    jvmArgs("-Dbooblik.bench.dir=$measurementDir")
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
        // `./gradlew :booblik-benchmark:mainWriterBenchmark` — the writer actor alone, at full
        // rigour. For A/B-ing a change to the write path against itself in one sitting, which is
        // the only comparison rule 3 allows; the full run takes thirteen minutes and most of it
        // measures paths the change cannot touch.
        register("writer") {
            common()
            warmups = 5
            iterations = 10
            include("PartitionWriterBenchmark")
        }
        // `./gradlew :booblik-benchmark:mainFlowBenchmark` — the Flow machinery with the socket
        // taken out, which is the only way this question has an answer: over a network the
        // pass-to-pass spread buries it (M-76).
        register("flow") {
            common()
            warmups = 5
            iterations = 10
            include("FlowOverheadBenchmark")
        }
        // `./gradlew :booblik-benchmark:mainCiBenchmark` — what a shared runner is allowed to
        // measure, and nothing else.
        //
        // Everything bounded by a disk barrier is excluded, because on a hosted runner those rows
        // are noise wearing a tight confidence interval. Measured, not assumed (замер 19): the
        // durability probe run twice on runners of the same class put `fsync` at 46.50 ms and then
        // at 75.81 ms, with `msync` swapping places with it — while JMH reported ±1 % on the mapped
        // path in the same session. The narrow interval says ten iterations agreed with each other,
        // not that the number says anything about the code.
        //
        // Leaving those rows in the report cost a full investigation: the flush-every-append rows
        // showed the two write paths swapping places against M-46, and that reading survived until
        // both paths were reproduced side by side on the same runner and turned out to be
        // indistinguishable.
        register("ci") {
            common()
            warmups = 5
            iterations = 10
            exclude("GroupCommitBenchmark")
            param("flushEveryAppend", false)
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

// Probes are not benchmarks and deliberately do not live under JMH. Each answers one question that
// is about a *shape* rather than an average — how a barrier behaves after another barrier, how a
// scan scales, how throughput decays over a minute — and JMH's job is to hide exactly that kind of
// variation. They are ordinary programs with a `main`, run on demand.
val probes =
    mapOf(
        "probeDurability" to "M-24: does msync promise what fsync promises",
        "probeStartup" to "M-23: how fast recovery scans a log",
        "probeSustainedWrite" to "M-26: throughput once the log outgrows memory",
        "probeLoad" to "M-33/M-34: end-to-end RPS and latency percentiles over a socket",
        "probeRemoteLoad" to "M-38: the same load against a broker on another machine",
        "probeSubscription" to "M-74: idle traffic and what the subscription Flow costs",
    )

probes.forEach { (task, description) ->
    tasks.register<JavaExec>(task) {
        group = "verification"
        this.description = description
        mainClass.set("ru.workinprogress.booblik.benchmark.probe.${task.removePrefix("probe")}Probe")
        classpath = sourceSets["main"].runtimeClasspath
        // Arguments come from `-Pargs="..."` so a probe can be pointed at the other write path
        // without editing it.
        argumentProviders.add {
            (project.findProperty("args") as String?)?.split(" ")?.filter(String::isNotEmpty).orEmpty()
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
