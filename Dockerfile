# syntax=docker/dockerfile:1

# Объявлено до первого `FROM`: аргумент, который используется в `FROM`, обязан быть глобальным —
# объявленный внутри стадии, он туда уже не виден.
ARG RUNTIME_BASE=eclipse-temurin:25-jre

# Сборка идёт внутри образа, чтобы результат не зависел от того, что стоит у собирающего.
# JDK, а не JRE: здесь компилируют.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Обёртка и каталог версий копируются первыми: они меняются реже исходников, и слой с
# зависимостями переиспользуется между сборками.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY booblik-core ./booblik-core
COPY booblik-client ./booblik-client
COPY booblik-net ./booblik-net
COPY booblik-app ./booblik-app
# Gradle требует, чтобы каждый включённый в settings модуль существовал, — поэтому копируется и
# тот, который образу не нужен. Он не собирается: `:booblik-app:installDist` до него не доходит,
# платится только конфигурацией.
COPY booblik-benchmark ./booblik-benchmark
RUN ./gradlew --no-daemon :booblik-app:installDist

# `-jre`, а не `-jdk`: компилятор в рантайме не нужен, а образ меньше на десятки мегабайт.
#
# База — параметр, и умолчание выбрано замером (M-83, замер 18). Alpine на musl даёт образ
# **в 2,6 раза меньше** — 197 МБ против 514, — и по пропускной способности от glibc на нашем
# стенде неотличим. Но «неотличим при разрешении ±8 %» слабее, чем «измерен на этом рантайме»,
# а все восемнадцать замеров проекта сняты на glibc. Поэтому умолчание — temurin, а musl
# доступен одной строкой:
#
#   docker build --build-arg RUNTIME_BASE=bellsoft/liberica-openjre-alpine-musl:25 .
FROM ${RUNTIME_BASE}
LABEL org.opencontainers.image.title="booblik" \
      org.opencontainers.image.description="Брокер сообщений: append-only лог, один процесс, без кластера" \
      org.opencontainers.image.licenses="Apache-2.0"

# Не root. Данные лежат на томе, и владелец каталога должен совпадать с пользователем процесса,
# иначе брокер не сможет создать первый сегмент — и узнает об этом уже в рантайме.
# Идентификатор закреплён, и не для красоты: файлы на bind-mount принадлежат числу, а не имени,
# поэтому «плавающий» uid между пересборками означал бы, что после обновления образа брокер не
# может писать в собственные данные. 10001 — потому что 1000 в базовом образе уже занят.
# Две ветки, потому что Alpine — busybox: `useradd` там нет, а у `adduser` другие флаги. Это и
# есть вся цена параметризации базы, и лучше она здесь, чем во втором `Dockerfile`, который
# разойдётся с первым на второй же правке.
RUN if command -v groupadd >/dev/null 2>&1; then \
        groupadd --system --gid 10001 booblik \
        && useradd --system --uid 10001 --gid booblik --home /var/lib/booblik booblik; \
    else \
        addgroup -S -g 10001 booblik \
        && adduser -S -u 10001 -G booblik -h /var/lib/booblik booblik; \
    fi \
    && mkdir -p /var/lib/booblik \
    && chown booblik:booblik /var/lib/booblik

COPY --from=build /src/booblik-app/build/install/booblik-app /opt/booblik

# Конфигурация — через окружение: `booblik.port` читается как `BOOBLIK_PORT` и так далее
# (см. BooblikConfig). Файл конфигурации не нужен, поэтому его здесь и нет.
ENV BOOBLIK_DATA_DIR=/var/lib/booblik \
    BOOBLIK_PORT=9092

# **JAVA_OPTS здесь намеренно не задаётся.**
# Профиль рантайма зашит в `DEFAULT_JVM_OPTS` стартового скрипта — те же шесть флагов, под
# которыми сняты все замеры. `JAVA_OPTS` их перебивает, и тогда образ поставляет процесс, который
# никто не мерил (риск 7). Если профиль надо изменить — он меняется в `brokerJvmArgs` корневого
# `build.gradle.kts`, и тогда меняются и замеры.

USER booblik
WORKDIR /var/lib/booblik
VOLUME ["/var/lib/booblik"]
EXPOSE 9092

# Проверка здоровья — METADATA, а не TCP-connect. Соединение ядро принимает в бэклог само,
# без участия процесса, поэтому connect не отличает работающий брокер от повешенного — ровно та
# неразличимость, которая стоила дня в M-64. Интервал задан явно: каждая проверка поднимает JVM
# (~150 мс, ресёрч §1.19), и на секундном интервале это было бы заметной долей машины.
HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD ["/opt/booblik/bin/booblik-health", "127.0.0.1", "9092"]

ENTRYPOINT ["/opt/booblik/bin/booblik-app"]
