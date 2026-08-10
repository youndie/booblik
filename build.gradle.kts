plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint)
}

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
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
