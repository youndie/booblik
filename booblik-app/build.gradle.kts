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
 * Второй скрипт в том же дистрибутиве — проверка здоровья (M-82).
 *
 * Отдельный запуск, а не флаг у `booblik-app`: перепутать «запусти брокер» и «спроси брокер»
 * должно быть невозможно, а `HEALTHCHECK` в образе всё равно вызывает команду, а не аргумент.
 * Профиль рантайма ему не нужен и не даётся: это короткоживущий процесс, которому 64 МиБ кучи
 * брокера ничего не говорят.
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
