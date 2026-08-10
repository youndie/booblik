plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint)
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

// The whole point of this block: the gate is one command. `./gradlew check` must run the tests
// AND ktlint, in every module, without anyone remembering a second line in CI.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // The formatter version is pinned here rather than left to the plugin default: otherwise the
    // style shifts when the plugin is bumped, which is exactly when nobody is looking at the diff.
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint)
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            // 25, not 21. Two things on the critical path are only available above 21:
            // `FileChannel.map(mode, offset, size, Arena)` — a mapping without the 2 GB ceiling and
            // with a deterministic release, unlike `MappedByteBuffer` (research §1.5); and virtual
            // threads that no longer pin the carrier on `synchronized` (JEP 491, 24), which is what
            // makes a thread-per-connection acceptor a fair baseline to measure against.
            jvmToolchain(25)

            // Public API is checked into the repository as `api/<module>.api` and compared on every
            // `check`. Two reasons, and the second is the one that matters here.
            //
            // The repository is about to be public, so a source change that quietly widens or
            // breaks the surface someone else compiles against is a real cost. But before that:
            // this project keeps a lot of machinery `internal` on purpose — the load driver, the
            // measurement directory, the wire codec's helpers — and there is no way to notice when
            // something slips out of `internal` except by reading every diff. The dump notices.
            //
            // This is the ABI validation built into the Kotlin plugin (`checkLegacyAbi` /
            // `updateLegacyAbi`), not the standalone `binary-compatibility-validator`. Verified in
            // the 2.4.10 artifact rather than assumed: `org.jetbrains.kotlin.gradle.dsl.abi.*` and
            // `KotlinLegacyAbiCheckTask` are there, and the standalone plugin is the thing it
            // replaced — one fewer dependency for the same `.api` format.
            // `:booblik-benchmark` is deliberately outside: it publishes nothing, and every new
            // probe would show up as an API change, which trains everyone to run `updateLegacyAbi`
            // without reading the diff — the exact habit this check exists to prevent.
            if (project.name != "booblik-benchmark") {
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
            useJUnitPlatform()
            // Tests run under the production footprint on purpose: an allocation the storage layer
            // is not supposed to make shows up here as an OOM in the gate, not in production.
            jvmArgs(rootProject.extra["brokerJvmArgs"] as List<String>)
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
