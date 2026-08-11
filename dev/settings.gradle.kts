// A build of its own, deliberately not part of the root `settings.gradle.kts`.
//
// The sample depends on the **published** client and on the image from GHCR, not on
// `project(":booblik-client")`. That is the whole point of it living here: a sample built against
// the project sees exactly what the project sees, including its packaging mistakes. A sample built
// against the published artefact is what caught 0.1.1 — a client whose POM put coroutines at
// runtime scope, so it resolved and then would not compile.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "booblik-dev"

include(":publisher")
include(":consumer")
include(":queue-worker")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "WipSnapshots"
            url = uri("https://reposilite.kotlin.website/snapshots")
        }
    }
}
