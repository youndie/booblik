#!/usr/bin/env bash
#
# Прогон Gradle-задачи на арендованной Linux-машине по **рабочему дереву**, без коммита.
#
# Отличие от `wsl-run.sh` — не в транспорте, а в предмете. Тот скрипт возит дерево в WSL, и всё,
# что оттуда приезжает, — числа WSL2: ext4 на VHDX поверх NTFS, ядро Microsoft, writeback решает
# Hyper-V (§1.12). Относительные сравнения это переживают, абсолютные — нет, а про долговечность
# WSL2 не свидетельствует вовсе. Здесь машина настоящая, и ради этого заведена веха M-46.
#
# Главная ловушка тут своя. У WSL это был путь в `/mnt/c`; здесь — **сетевой диск**. Арендованный
# сервер с примонтированным Volume выглядит и ведёт себя как обычный сервер, всё собирается и
# считает, а меряется сеть провайдера. Поэтому скрипт спрашивает у машины, на чём лежит каталог
# прогона, и отказывается работать на nfs/cifs/9p; вращающийся диск — предупреждение, а не отказ.
#
# Использование:
#   BOOBLIK_REMOTE_HOST=root@адрес ./ci/remote-run.sh                 # ./gradlew check
#   BOOBLIK_REMOTE_HOST=root@адрес ./ci/remote-run.sh :booblik-benchmark:mainBenchmark
#   BOOBLIK_REMOTE_RAW=1 ... ./ci/remote-run.sh :booblik-benchmark:probeDurability
#
# Настройки — через окружение:
#   BOOBLIK_REMOTE_HOST  пользователь@адрес (обязательно)
#   BOOBLIK_REMOTE_PATH  путь к чекауту, по умолчанию ~/booblik на той стороне
#   BOOBLIK_REMOTE_KEY   приватный ключ, по умолчанию выбирает ssh
#   BOOBLIK_REMOTE_RAW   непустое — печатать полный вывод (нужно для замеров)

set -euo pipefail

# Адрес и ключ задаются окружением и в репозитории не хранятся: это чужая частная
# инфраструктура, а не настройка проекта.
HOST="${BOOBLIK_REMOTE_HOST:?задайте BOOBLIK_REMOTE_HOST=пользователь@адрес}"

SSH_OPTS=(-o ConnectTimeout=20 -o BatchMode=yes)
[ -n "${BOOBLIK_REMOTE_KEY:-}" ] && SSH_OPTS+=(-i "$BOOBLIK_REMOTE_KEY")

REMOTE="${BOOBLIK_REMOTE_PATH:-}"
if [ -z "$REMOTE" ]; then
    REMOTE="$(ssh "${SSH_OPTS[@]}" "$HOST" 'echo $HOME/booblik' | tr -d '\r')"
fi
case "$REMOTE" in
    /*) ;;
    *) echo "путь $REMOTE должен быть абсолютным" >&2; exit 1 ;;
esac

# Проверка носителя идёт до синхронизации: незачем возить дерево туда, где мерить нечего.
# Смотрим на существующего предка пути — самого каталога может ещё не быть.
read -r FSTYPE SOURCE ROTA <<EOF
$(ssh "${SSH_OPTS[@]}" "$HOST" "bash -s" <<REMOTE_PROBE
set -eu
p="$REMOTE"
while [ ! -e "\$p" ]; do p="\$(dirname "\$p")"; done
fs="\$(findmnt -no FSTYPE -T "\$p")"
src="\$(findmnt -no SOURCE -T "\$p")"
pk="\$(lsblk -no PKNAME "\$src" 2>/dev/null | head -1 || true)"
rota="\$(cat "/sys/block/\$pk/queue/rotational" 2>/dev/null || echo '?')"
echo "\$fs \$src \$rota"
REMOTE_PROBE
)
EOF

case "$FSTYPE" in
    nfs|nfs4|cifs|smb3|9p|virtiofs)
        echo "каталог прогона лежит на $FSTYPE ($SOURCE) — это сетевое хранилище," >&2
        echo "и замеры диска оттуда описывают сеть провайдера, а не носитель" >&2
        exit 1 ;;
esac
[ "$ROTA" = "1" ] && echo "! диск $SOURCE вращающийся — числа сравнивать с NVMe-прогонами нельзя" >&2

TASKS=("$@")
if [ ${#TASKS[@]} -eq 0 ]; then TASKS=("check"); fi
# Каждый аргумент экранируется отдельно: удалённый bash разбирает то, что мы ему подставим, ещё
# раз, и `-Pargs="PRODUCE SELECTOR ZERO_COPY"` без этого приезжает туда тремя аргументами Gradle.
# Проявляется не ошибкой аргумента, а «task SELECTOR not found» — то есть выглядит как опечатка
# в вызове, а не как поломка транспорта.
TASKS_QUOTED="$(printf ' %q' "${TASKS[@]}")"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "→ синхронизация рабочего дерева в $HOST:$REMOTE (${FSTYPE} на ${SOURCE})"
# `--delete` обязателен: синхронизация через tar удалённые файлы не убирает, и выглядит это как
# «правка не подействовала» — старый файл продолжает собираться рядом с новым.
rsync -az --delete \
    --exclude 'build/' \
    --exclude '.gradle/' \
    --exclude '.git/' \
    --exclude '.kotlin/' \
    --exclude '.DS_Store' \
    -e "ssh ${SSH_OPTS[*]}" \
    "$ROOT/" "$HOST:$REMOTE/"

echo "→ ${TASKS[*]}"

# JDK на той стороне ставить не нужно: `settings.gradle.kts` подключает foojay-резолвер, и Gradle
# скачивает тулчейн 25 сам. Первый прогон из-за этого длиннее.
ssh "${SSH_OPTS[@]}" "$HOST" "bash -s" <<REMOTE_SCRIPT
set -euo pipefail
# Ничто не должно спросить пароль: интерактивного ввода на той стороне нет, и вопрос вешает
# сессию намертво вместо того, чтобы упасть с внятной ошибкой.
export GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/bin/true
cd "$REMOTE"
# Флаг подставляется здесь, а не читается там: окружение по ssh не едет, и переменная,
# оставленная на разбор удалённому bash, всегда пуста.
if [ -n "${BOOBLIK_REMOTE_RAW:-}" ]; then
    ./gradlew$TASKS_QUOTED --console=plain 2>&1
else
    ./gradlew$TASKS_QUOTED --console=plain 2>&1 | grep -E '^e: |FAILED|tests? completed|BUILD' | tail -20
fi
REMOTE_SCRIPT
