plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // `api`, потому что сервер отдаёт наружу типы кодека (`ErrorCode`, `Protocol`), а тесты
    // и приложение работают с клиентом.
    api(project(":booblik-client"))
    // `api`, not `implementation`: anything talking to this module handles Offset and TopicName,
    // so hiding the core types would only force every consumer to re-declare the same dependency.
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    // Утверждения о Flow: подписка — это Flow, и Turbine написан ровно под это.
    testImplementation(libs.turbine)
}
