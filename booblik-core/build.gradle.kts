plugins {
    alias(libs.plugins.kotlin.jvm)
}

apply(from = rootProject.file("publishing.gradle.kts"))

dependencies {
    // `api` because the storage speaks in these types: `Offset`, `TopicName`, `PartitionId` and
    // `AckPolicy` are all over the public surface, and they live in the multiplatform module since
    // M-134 — the wire is what a client and a broker have to agree on, so the types that go on it
    // belong where both can name them.
    api(project(":booblik-protocol"))

    // `api`, not `implementation`, and the ABI dump is the evidence: `PartitionWriter` hands out a
    // `StateFlow` and takes a `CoroutineScope`, so coroutines are part of the surface rather than a
    // detail behind it. Declared as `implementation` they land in the POM at runtime scope, and a
    // consumer of the published artefact cannot compile against the API at all — which is exactly
    // how 0.1.1 shipped.
    api(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    // For M-62: the no-locks rule is checked by reading the bytecode this module compiles to.
    testImplementation(libs.asm)
}
