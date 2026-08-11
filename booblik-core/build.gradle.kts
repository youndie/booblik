plugins {
    alias(libs.plugins.kotlin.jvm)
}

apply(from = rootProject.file("publishing.gradle.kts"))

dependencies {
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    // For M-62: the no-locks rule is checked by reading the bytecode this module compiles to.
    testImplementation(libs.asm)
}
