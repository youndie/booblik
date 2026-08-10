// Lets Gradle fetch the JDK 25 toolchain itself. Without it the project only builds on a machine
// where somebody already installed 25 by hand — which the Linux box used for measurements had not,
// and the failure there is a toolchain error that says nothing about what to install.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "booblik"

include(":booblik-core")
include(":booblik-net")
include(":booblik-app")
include(":booblik-benchmark")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
