dependencies {
    implementation(project(":common"))
    implementation(libs.booblik.client)
    implementation(libs.kafka.clients)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("ru.workinprogress.booblik.dev.relay.MainKt")
}
