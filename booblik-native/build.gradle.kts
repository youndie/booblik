plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

/*
 * The publisher for Kotlin/Native.
 *
 * A target of the same protocol rather than a fifth reimplementation: the codec, the ids and the
 * partitioner all come from `:booblik-protocol`, so this module is a socket and the shape of an
 * API. That is the whole argument for M-134 having been done as a multiplatform split instead of
 * another from-scratch client.
 *
 * One dependency, kotlinx-coroutines, and it was decided on evidence rather than taste (M-134а).
 *
 * `Connection` stays blocking. `Producer` is the accumulator, and it needs somewhere to block: on
 * Kotlin/Native **`Dispatchers.IO` is `internal`** — checked by compiling against coroutines 1.11.0,
 * not read in the documentation, which says otherwise — so there is no IO pool to offload a
 * blocking socket onto, and using `Dispatchers.Default` would tie up one of as many threads as
 * there are cores. `newSingleThreadContext` does work, so the producer owns a thread and both the
 * loop and the socket live on it.
 *
 * The dependency costs this module's "nothing at all" property and costs its audience nothing: the
 * consumers are Ktor services that already have coroutines — and they are also the reason the
 * accumulator is worth having, being the concurrent callers a linger window has anything to wait
 * for.
 */
kotlin {
    linuxX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":booblik-protocol"))
            // `api`, not `implementation`: `Producer.send` hands back a `CompletableDeferred` and
            // its constructor takes a `CoroutineScope`, so coroutines are part of the surface
            // rather than a detail behind it. Declared as `implementation` they land in the POM at
            // runtime scope, and a consumer cannot compile against the API at all — which is
            // exactly how 0.1.1 shipped.
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    // Inside the `kotlin { }` block, like `:booblik-protocol`. Configured outside it the call is
    // accepted and quietly does nothing — no dump appears, and a gate that produces no artefact to
    // compare against is a gate that passes for the wrong reason.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        referenceDumpDir.set(rootProject.layout.projectDirectory.dir("api"))
    }
}

tasks.named("check") {
    dependsOn(tasks.named("checkKotlinAbi"))
}
