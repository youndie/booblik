plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ru.workinprogress.sborka.jvm")
    id("ru.workinprogress.sborka.lint")
}

dependencies {
    // `api`, not `implementation`: the server hands out codec types (`ErrorCode`, `Protocol`), and
    // both the tests and the application work through the client, so hiding it would only force
    // every consumer to declare the same dependency again.
    api(project(":booblik-client"))
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    // A subscription is a Flow, and Turbine is written for exactly that.
    testImplementation(libs.turbine)
}
