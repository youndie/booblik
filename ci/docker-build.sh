#!/usr/bin/env bash
#
# Собирает образ из дистрибутива, который **прошёл гейт**.
#
# Это единственное отличие от прямого `docker build .`, и оно существенное: в образе нет
# сборочной стадии, поэтому сам по себе `docker build` возьмёт то, что лежит в
# `booblik-app/build/install` прямо сейчас — хоть месячной давности, хоть от ветки, где падают
# тесты. Порядок здесь и есть гарантия.
#
# Использование:
#   ./ci/docker-build.sh                 # booblik:local
#   ./ci/docker-build.sh booblik:1.0     # своё имя
#   BOOBLIK_SKIP_GATE=1 ./ci/docker-build.sh   # только для отладки самого образа

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${1:-booblik:local}"

GATED="из дистрибутива, прошедшего гейт"
if [ -z "${BOOBLIK_SKIP_GATE:-}" ]; then
    echo "→ гейт"
    "$ROOT/gradlew" -p "$ROOT" check --console=plain -q
else
    echo "! гейт пропущен по BOOBLIK_SKIP_GATE"
    GATED="БЕЗ ГЕЙТА — из непроверенного кода"
fi

echo "→ дистрибутив"
"$ROOT/gradlew" -p "$ROOT" :booblik-app:installDist --console=plain -q

echo "→ образ $IMAGE"
docker build -t "$IMAGE" "$ROOT"
docker images --format '   {{.Repository}}:{{.Tag}}  {{.Size}}' "$IMAGE" | head -1
# Итоговая строка обязана говорить правду о том, что произошло: раньше она печатала «прошедшего
# гейт» и при пропущенном гейте тоже.
echo "✓ собран $GATED"
