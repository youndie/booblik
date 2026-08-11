plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":booblik-net"))
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("ru.workinprogress.booblik.app.MainKt")
    // The broker's own footprint, not the build's. Same list as the tests and the benchmarks run
    // under — a runnable artifact that starts with different flags than everything was measured
    // with would make every number in docs/benchmarking.md describe something else.
    applicationDefaultJvmArgs = rootProject.extra["brokerJvmArgs"] as List<String>
}

/*
 * A second script in the same distribution — the health check (M-82).
 *
 * Its own entry point rather than a flag on `booblik-app`: confusing "start the broker" with "ask
 * the broker" has to be impossible, and `HEALTHCHECK` in the image invokes a command anyway, not an
 * argument. It neither needs nor gets the runtime profile: this is a short-lived process, and the
 * broker's 64 MiB heap means nothing to it.
 */
val healthScripts =
    tasks.register<CreateStartScripts>("healthStartScripts") {
        mainClass.set("ru.workinprogress.booblik.app.Health")
        applicationName = "booblik-health"
        outputDir =
            layout.buildDirectory
                .dir("scripts-health")
                .get()
                .asFile
        classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
    }

distributions {
    named("main") {
        contents {
            from(healthScripts) { into("bin") }
        }
    }
}
