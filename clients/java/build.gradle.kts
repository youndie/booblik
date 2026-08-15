plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.youndie.booblik"
version = "0.1.0"

/*
 * A client for booblik in plain Java.
 *
 * **No dependencies**, which is the whole reason it exists rather than a facade over the Kotlin
 * client. A facade would have solved the syntax — Kotlin mangles the names of functions with
 * `value class` parameters, so `topic-7zVQyJo` and `produce-iPAU26k` are not merely awkward from
 * Java but unspellable, a hyphen being illegal in an identifier — and left the real objection
 * standing: it would still have dragged kotlin-stdlib (1.8 MB) and kotlinx-coroutines (1.5 MB)
 * into a service that chose Java precisely to avoid them.
 *
 * Only the tests have a dependency, JUnit, because the JDK has no test runner in the box. Same
 * trade as the .NET client.
 */
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }

    // 17, not the toolchain's own version and emphatically not the broker's 25. The broker needs 25
    // for the FFM mapping; this client needs nothing newer than records and `var`, and a library
    // compiled for 25 would be unusable to most of the people it is written for.
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    // Warnings are failures. This is a protocol implementation whose mistakes are silent by nature,
    // and a build that prints them and carries on is a build nobody reads.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

/*
 * The conformance client, as a runnable jar rather than a second module: it is one class, and the
 * harness needs a command it can exec.
 */
val conformanceJar by tasks.registering(Jar::class) {
    archiveClassifier = "conformance"
    from(sourceSets.main.get().output)
    manifest {
        attributes["Main-Class"] = "ru.workinprogress.booblik.java.Conformance"
    }
}

/*
 * Published like the JVM modules in the main build, and configured here rather than by sharing
 * their `publishing.gradle.kts`: this is a **separate Gradle build** with its own wrapper, so there
 * is no root project to apply a script from. The property names are the same ones on purpose —
 * one set of secrets covers every Maven publication in the repository.
 *
 * Without configuration only `publishToMavenLocal` works, which is the right default: whoever
 * builds booblik has their own destination.
 */
publishing {
    repositories {
        maven {
            name = "booblikRepo"
            url =
                uri(
                    providers.gradleProperty("BOOBLIK_REPO_URL").orNull
                        ?: System.getenv("BOOBLIK_REPO_URL")
                        ?: "https://reposilite.kotlin.website/snapshots",
                )
            credentials {
                username =
                    providers.gradleProperty("BOOBLIK_REPO_USER").orNull
                        ?: System.getenv("BOOBLIK_REPO_USER")
                password =
                    providers.gradleProperty("BOOBLIK_REPO_SECRET").orNull
                        ?: System.getenv("BOOBLIK_REPO_SECRET")
            }
        }
    }

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "booblik-java"
                description = "A client for booblik, a message broker on an append-only log"
                url = "https://github.com/youndie/booblik"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                // Maven Central refuses a POM without these three, and that decision is open
                // (M-156). They cost nothing in reposilite.
                developers {
                    developer {
                        id = "youndie"
                        url = "https://github.com/youndie"
                    }
                }
                scm {
                    url = "https://github.com/youndie/booblik"
                    connection = "scm:git:https://github.com/youndie/booblik.git"
                    developerConnection = "scm:git:ssh://git@github.com/youndie/booblik.git"
                }
            }
        }
    }
}
