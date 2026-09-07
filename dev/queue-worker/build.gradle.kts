dependencies {
    implementation(libs.booblik.client)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("ru.workinprogress.booblik.dev.queue.MainKt")
}

/*
 * A second script in the same distribution: the queue report (M-103).
 *
 * Its own entry point rather than a flag, for the same reason the broker ships `booblik-health`
 * separately — "run a worker" and "tell me about the queue" must not be one command apart.
 */
val reportScripts =
    tasks.register<CreateStartScripts>("reportStartScripts") {
        mainClass.set("ru.workinprogress.booblik.dev.queue.Report")
        applicationName = "queue-report"
        outputDir =
            layout.buildDirectory
                .dir("scripts-report")
                .get()
                .asFile
        classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
    }

distributions {
    named("main") {
        contents {
            from(reportScripts) { into("bin") }
        }
    }
}
