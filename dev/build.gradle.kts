plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint)
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "application")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // The sample is held to the same style as the broker it demonstrates. Without this the one
    // directory a stranger reads first is the one nothing checks.
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint)
    }

    dependencies { add("testImplementation", kotlin("test")) }

    // No `repositories { }` here on purpose. Declaring them per project **replaces** the ones from
    // `settings.gradle.kts` — the default `repositoriesMode` is `PREFER_PROJECT` — and the failure
    // is a plain "Could not find io.github.youndie.booblik:booblik-client", which reads like the
    // artefact is missing rather than like the repository was dropped.

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    /*
     * The broker's runtime profile is deliberately **not** applied here.
     *
     * 64 MiB and SerialGC are a constraint on the broker, and the numbers in docs/benchmarking.md
     * only mean anything under it. A sample service is a different program with different work,
     * and giving it the same flags would suggest the profile is a house style rather than a
     * property of the thing it was measured on.
     */
}
