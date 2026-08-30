plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    // Declared here as well, and for the same reason: the Kotlin plugin lands on the build classpath
    // once, so a module asking for a *versioned* multiplatform plugin fails with "already on the
    // classpath with an unknown version" rather than anything about multiplatform. The sborka
    // plugins are declared the same way and for the same reason.
    alias(libs.plugins.kotlin.multiplatform) apply false
    // The benchmark module's two, declared here so its own `plugins { }` block can name them without
    // a version — the same rule the Kotlin plugins above follow.
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.benchmark) apply false
    alias(libs.plugins.sborkaJvm) apply false
    alias(libs.plugins.sborkaKmp) apply false
    alias(libs.plugins.sborkaLint) apply false
    alias(libs.plugins.sborkaPublish) apply false
}

/**
 * The runtime footprint booblik is designed to live in. Not a tuning knob — a **constraint**, and
 * the numbers in docs/benchmarking.md are only meaningful under it.
 *
 * Declared once and applied to both the tests and the forked benchmark JVM, so a change cannot
 * land in one and not the other. Two of these flags interact with the storage layer in ways worth
 * writing down:
 *
 * * `-Xmx64M` and `-XX:MaxDirectMemorySize=32M` do **not** bound the mapped segment. Memory from
 *   `FileChannel.map` is neither heap nor direct-buffer memory, so `MAPPED` can address a 512 MiB
 *   segment inside a 64 MiB heap. That is a property of the mapping, not luck — but it also means
 *   these two flags give no protection at all against a mapping that is too large.
 * * `-XX:MaxDirectMemorySize=32M` does bound `FileChannelSegmentWriter`, which keeps one direct
 *   buffer of 4 bytes per segment. Room for roughly eight million segments; not the binding
 *   constraint, and if it ever becomes one, something else is wrong.
 */
val defaultBrokerJvmArgs =
    listOf(
        "-XX:+UseSerialGC",
        "-XX:ReservedCodeCacheSize=32M",
        "-XX:MaxDirectMemorySize=32M",
        "-Xss256k",
        "-XX:MaxMetaspaceSize=80M",
        "-Xmx64M",
    )

/**
 * `-Pbooblik.jvmArgs="-Xmx4G -XX:+UseG1GC"` replaces the whole list for one invocation.
 *
 * For answering "what does the footprint cost", and nothing else. It is deliberately all-or-nothing
 * rather than a merge: a half-overridden profile is a third configuration nobody described, and the
 * number it produces would belong to neither column of the comparison.
 */
val brokerJvmArgs: List<String> =
    (project.findProperty("booblik.jvmArgs") as String?)
        ?.split(" ")
        ?.filter(String::isNotEmpty)
        ?: defaultBrokerJvmArgs

/**
 * Tells [RuntimeFootprint] that the profile was replaced on purpose, so it prints what it got
 * instead of refusing to start. Only ever set when the override was actually passed.
 */
val footprintOverridden = project.hasProperty("booblik.jvmArgs")

extra["brokerJvmArgs"] =
    if (footprintOverridden) brokerJvmArgs + "-Dbooblik.footprintOverridden=true" else brokerJvmArgs

// The group, the version, the toolchain, the ktlint wiring, the test platform and the test logging
// used to live here, in `allprojects` and `subprojects`, and the publication in
// `publishing.gradle.kts`. They come from `ru.workinprogress.sborka` now, applied per module, with
// the numbers one line each in `gradle.properties` and the reasons kept beside them.
//
// What stays is what is booblik's: the runtime footprint above, and the ABI dump that says which
// declarations left `internal`.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            // Public API is checked into the repository as `api/<module>.api` and compared on every
            // `check`. Two reasons, and the second is the one that matters here.
            //
            // The repository is public, so a source change that quietly widens or breaks the surface
            // someone else compiles against is a real cost. But before that: this project keeps a lot
            // of machinery `internal` on purpose — the load driver, the measurement directory, the
            // wire codec's helpers — and there is no way to notice when something slips out of
            // `internal` except by reading every diff. The dump notices.
            //
            // This is the ABI validation built into the Kotlin plugin (`checkKotlinAbi` /
            // `updateKotlinAbi`), not the standalone `binary-compatibility-validator`.
            // `:booblik-benchmark` is deliberately outside: it publishes nothing, and every new probe
            // would show up as an API change, which trains everyone to update the dump without
            // reading the diff — the exact habit this check exists to prevent.
            // `:booblik-conformance` is outside for the same reason: it is a fixture for the
            // conformance harness, its whole surface is a `main`, and nobody can depend on it.
            if (project.name !in setOf("booblik-benchmark", "booblik-conformance")) {
                @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
                abiValidation {
                    // Calling the block is what enables it — there is no `enabled` flag any more,
                    // and the dump directory moved up out of `legacyDump`. Both changed inside
                    // 2.4.x, which is why the DSL is opt-in: it is expected to move again.
                    referenceDumpDir.set(rootProject.layout.projectDirectory.dir("api"))
                }
                // The check is not wired into `check` by the plugin, and a gate nobody runs is not
                // a gate. One command stays one command (project rule 1).
                tasks.named("check") { dependsOn(tasks.named("checkKotlinAbi")) }
            }
        }

        tasks.withType<Test>().configureEach {
            // Tests run under the production footprint on purpose: an allocation the storage layer
            // is not supposed to make shows up here as an OOM in the gate, not in production.
            jvmArgs(rootProject.extra["brokerJvmArgs"] as List<String>)
        }
    }
}
