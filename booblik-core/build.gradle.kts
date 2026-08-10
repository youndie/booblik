plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
