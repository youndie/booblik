import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

/*
 * Публикация в Maven-репозиторий (M-93).
 *
 * Адрес, логин и пароль берутся из Gradle-свойств `BOOBLIK_REPO_URL` / `BOOBLIK_REPO_USER` /
 * `BOOBLIK_REPO_SECRET` либо из одноимённых переменных окружения. В репозиторий не попадает
 * ничего из этого: у каждого, кто собирает booblik, назначение своё, а без настройки остаётся
 * `publishToMavenLocal`.
 *
 * Скрипт применяется к **двум** модулям, и второй здесь не для симметрии. `booblik-client`
 * зависит от `booblik-core` через `api`, поэтому опубликованный в одиночку клиент — это POM
 * со ссылкой на артефакт, которого в репозитории нет. Сервер (`:booblik-net`, `:booblik-app`)
 * и стенд (`:booblik-benchmark`) наружу не выкладываются.
 *
 * Блок `plugins { }` здесь недоступен — это применяемый скрипт, а не build-файл, поэтому
 * плагин подключается `apply`, а расширение настраивается через `configure`.
 */
apply(plugin = "maven-publish")

configure<PublishingExtension> {
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
                name.set(project.name)
                // По-английски, как и всё, что видит потребитель: описание артефакта показывают
                // и Maven Central, и любой браузер репозитория.
                description.set(
                    "A message broker on an append-only log: segments, reads by a numeric offset, " +
                        "one process and no cluster",
                )
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
