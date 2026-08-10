plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":booblik-core"))
    // `api`, not `implementation`: anything talking to this module handles Offset and TopicName,
    // so hiding the core types would only force every consumer to re-declare the same dependency.
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
