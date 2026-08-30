plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
}

/*
 * The Kotlin/Native client under test, for `conformance/harness`.
 *
 * A module of its own rather than an executable inside `:booblik-native`, for one reason worth the
 * extra build file: a Kotlin/Native binary is linked from the target's own compilation, so an
 * entry point living in the library would end up in the published klib and in its ABI dump. A
 * fixture has no business in the surface consumers depend on.
 *
 * The JVM has the same split for the same reason — `:booblik-conformance` beside `:booblik-client`.
 *
 * Publishes nothing, so `publishing.gradle.kts` is not applied and there is no ABI validation: the
 * whole surface is a `main`.
 */
kotlin {
    listOf(linuxX64(), macosArm64()).forEach { target ->
        target.binaries.executable {
            baseName = "conformance"
            entryPoint = "ru.workinprogress.booblik.native.conformance.main"
        }
        // The accumulator's measurement (M-134а). A second binary in the same module because it
        // needs the same thing the conformance client does — a real broker and no publishing.
        target.binaries.executable("probe") {
            baseName = "probe"
            entryPoint = "ru.workinprogress.booblik.native.conformance.probe"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":booblik-native"))
        }
    }
}
