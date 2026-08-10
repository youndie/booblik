#!/usr/bin/env bash
#
# Проверяет **образ**, а не сборку.
#
# `ci/smoke.sh` проверяет дистрибутив: что он стартует, обслуживает и переживает рестарт. Между
# дистрибутивом и образом помещается ещё один класс поломок, которого не видит ни то, ни другое:
# не тот пользователь и нет прав на том, конфигурация не доехала из окружения, healthcheck зовёт
# несуществующий путь, и — главное — профиль рантайма перебит `JAVA_OPTS`, после чего поставляется
# процесс, который никто не мерил (риск 7).
#
# Использование:
#   ./ci/docker-smoke.sh              # соберёт образ booblik:smoke и проверит его
#   BOOBLIK_IMAGE=booblik:1.0 ./ci/docker-smoke.sh   # проверит готовый образ, не собирая

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${BOOBLIK_IMAGE:-booblik:smoke}"
NAME="booblik-smoke-$$"
PORT="${BOOBLIK_SMOKE_PORT:-19099}"

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ -z "${BOOBLIK_IMAGE:-}" ]; then
    echo "→ сборка образа $IMAGE"
    docker build -t "$IMAGE" "$ROOT" >/dev/null
fi

echo "→ запуск"
docker run -d --name "$NAME" -p "$PORT:9092" -e BOOBLIK_TOPICS=smoke:2 "$IMAGE" >/dev/null
for _ in $(seq 1 60); do
    docker logs "$NAME" 2>&1 | grep -q "booblik listening" && break
    sleep 0.5
done
docker logs "$NAME" 2>&1 | grep -q "booblik listening" || {
    echo "брокер в контейнере не поднялся:"; docker logs "$NAME" 2>&1 | tail -20; exit 1
}

echo "→ конфигурация доехала из окружения"
docker logs "$NAME" 2>&1 | grep -q "topics=smoke:2" || {
    echo "BOOBLIK_TOPICS не применился:"; docker logs "$NAME" 2>&1 | head -10; exit 1
}
echo "   topics=smoke:2 из BOOBLIK_TOPICS"

# M-84. Профиль зашит в стартовый скрипт, но `JAVA_OPTS` в чужом `Dockerfile` перебивает его
# одной строкой, и заметить это снаружи было нечем. Брокер печатает свои аргументы при старте
# именно ради этой проверки.
echo "→ профиль рантайма доехал до процесса"
JVM_LINE="$(docker logs "$NAME" 2>&1 | grep -m1 'jvm:' || true)"
[ -n "$JVM_LINE" ] || { echo "брокер не напечатал строку jvm:"; exit 1; }
for flag in -XX:+UseSerialGC -XX:ReservedCodeCacheSize=32M -XX:MaxDirectMemorySize=32M \
            -Xss256k -XX:MaxMetaspaceSize=80M -Xmx64M; do
    case "$JVM_LINE" in
        *"$flag"*) ;;
        *) echo "в образе нет флага $flag; процесс запущен не с тем профилем, под которым сняты замеры"
           echo "  фактически: $JVM_LINE"; exit 1 ;;
    esac
done
echo "   все шесть флагов на месте"

echo "→ healthcheck отвечает через METADATA"
docker exec "$NAME" /opt/booblik/bin/booblik-health 127.0.0.1 9092
docker exec "$NAME" /opt/booblik/bin/booblik-health 127.0.0.1 9999 >/dev/null 2>&1 && {
    echo "healthcheck сказал 'здоров' про порт, где никого нет"; exit 1
}
echo "   и отличает живой порт от мёртвого"

echo "→ процесс не root"
WHO="$(docker exec "$NAME" id -un)"
[ "$WHO" = "booblik" ] || { echo "процесс работает под $WHO, ожидался booblik"; exit 1; }
echo "   пользователь $WHO"

echo "→ данные пишутся на том, и сегмент разрежён"
docker exec "$NAME" /opt/booblik/bin/booblik-health 127.0.0.1 9092 >/dev/null
SEG="$(docker exec "$NAME" sh -c 'ls /var/lib/booblik/smoke-0/*.log 2>/dev/null | head -1' || true)"
if [ -n "$SEG" ]; then
    read -r APPARENT BLOCKS <<<"$(docker exec "$NAME" stat -c '%s %b' "$SEG")"
    echo "   сегмент: видимый $APPARENT Б, занято $((BLOCKS / 2)) КиБ"
    [ "$BLOCKS" -lt 2048 ] || { echo "сегмент материализовался целиком — разрежённость потеряна"; exit 1; }
fi

echo "✓ образ стартует под нужным пользователем, с нужным профилем и отвечает на METADATA"
