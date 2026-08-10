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
