rootProject.name = "booblik"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content {
                // Both groups on purpose. The portfolio is moving to `io.github.youndie` and sborka
                // is already there — the plugin marker and the jar behind it are under the new one.
                // The old one is held by the library versions published before the move: they are
                // still on the server and resolve as before.
                includeGroupByRegex("io\\.github\\.youndie.*")
                includeGroupByRegex("ru\\.workinprogress.*")
            }
        }
    }
}

plugins {
    // Lets Gradle fetch the JDK 25 toolchain itself. Without it the project only builds on a machine
    // where somebody already installed 25 by hand — which the Linux box used for measurements had
    // not, and the failure there is a toolchain error that says nothing about what to install.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // mavenCentral() with its content filters, the shared `wip` catalog, and the check that this
    // repository's `.editorconfig` is the one the rest of them use.
    id("io.github.youndie.sborka.settings") version "0.3.0.41"
}

include(":booblik-protocol")
include(":booblik-native")
include(":booblik-native-conformance")
include(":booblik-core")
include(":booblik-client")
include(":booblik-net")
include(":booblik-app")
include(":booblik-benchmark")
include(":booblik-conformance")
