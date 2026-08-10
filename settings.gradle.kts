rootProject.name = "booblik"

include(":booblik-core")
include(":booblik-benchmark")

// `:booblik-net` is deliberately absent: there is no network code yet, and an empty module would
// claim otherwise. It arrives with M3 — see BACKLOG.md.

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
