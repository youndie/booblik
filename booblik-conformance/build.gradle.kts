plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

/*
 * The reference client for the conformance contract.
 *
 * Depends on `:booblik-client` as a project rather than on the published artefact, unlike `dev/`.
 * The two are checking different things and the difference is deliberate: `dev/` exists to catch
 * defects in the **delivery** — a POM that resolves and will not compile — and has to build the way
 * a stranger would. This is a fixture for the harness, and it has to describe the client as it is
 * in this working tree, including changes that have not been published yet.
 *
 * Publishes nothing, so `publishing.gradle.kts` is not applied and the module is outside ABI
 * validation (root `build.gradle.kts`) — a fixture has no public surface anyone can depend on.
 */
dependencies {
    implementation(project(":booblik-client"))
}

application {
    mainClass.set("ru.workinprogress.booblik.conformance.MainKt")
    // Not the broker's runtime profile. This is a short-lived process that starts once per check,
    // and a 64 MiB heap with SerialGC would be describing a JVM nobody is measuring here.
}
