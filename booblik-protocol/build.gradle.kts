plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

/*
 * The half of the protocol a **client** needs, and nothing else.
 *
 * Split out in M-134, when a Kotlin/Native publisher stopped being hypothetical. The line it is cut
 * along was already there: a client never decodes requests and a server never encodes them, so the
 * two halves share only `Protocol.kt` — a hundred lines of constants and enums with no `ByteBuffer`
 * in them at all.
 *
 * That is why `RequestDecoder` and `ResponseEncoder` stayed in `:booblik-client` on the JVM. They are
 * the server's half, `RequestDecoder` sits on the PRODUCE hot path, and moving it would have meant
 * re-measuring the broker to prove nothing had been lost — a cost with no buyer, since no native
 * broker is planned.
 *
 * Everything here is `ByteArray` and index arithmetic. Not a portability compromise: the JVM client
 * hands the finished array to `ByteBuffer.wrap`, which allocates a view and copies nothing.
 */
kotlin {
    jvm()

    // linuxX64 because that is where the consumers are — tracy, shildik, mongkn, hub-backend, the
    // stocker bot. macosArm64 so the tests can be run on the machine they are written on: a
    // linuxX64 test binary does not run on macOS at all, so without this target native code would
    // only ever be exercised on another host.
    linuxX64()
    macosArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    // Configured here rather than picked up from the root, which wires ABI validation through
    // `plugins.withId("org.jetbrains.kotlin.jvm")` and so does not see a multiplatform module.
    // Skipping it would have been the quiet option and the wrong one: this module is published, and
    // `ByteWriter`/`ByteReader` are `internal` precisely so nobody depends on them — which is a
    // claim only the dump can keep honest.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        referenceDumpDir.set(rootProject.layout.projectDirectory.dir("api"))
    }
}

tasks.named("check") {
    dependsOn(tasks.named("checkKotlinAbi"))
}
