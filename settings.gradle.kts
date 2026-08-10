rootProject.name = "booblik"

include(":booblik-core")
include(":booblik-net")
include(":booblik-benchmark")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
