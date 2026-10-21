#!/usr/bin/env bash
# Скачивает исходники OpenGothic (MIT) закреплённой версии с подмодулями и накладывает наши патчи.
# Результат — third_party/opengothic (в .gitignore): его собирает модуль :app через CMake
# (./gradlew :app:assembleDebug; docs/ARCHITECTURE.md).
#
# Запуск один раз после клонирования репозитория и после смены opengothic.commit:
#   tools/fetch-opengothic.sh
set -euo pipefail

OPENGOTHIC_REPO="https://github.com/Try/OpenGothic.git"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OPENGOTHIC_COMMIT="$(sed -n 's/^opengothic\.commit=//p' "$ROOT/gradle.properties")"
[ -n "$OPENGOTHIC_COMMIT" ] || { echo "Нет opengothic.commit в gradle.properties" >&2; exit 1; }
DEST="$ROOT/third_party/opengothic"
PATCH="$ROOT/third_party/opengothic.patch"
PATCH_TEMPEST="$ROOT/third_party/opengothic-tempest.patch"

if [ ! -d "$DEST/.git" ]; then
    mkdir -p "$DEST"
    git -C "$DEST" init -q
    git -C "$DEST" remote add origin "$OPENGOTHIC_REPO"
fi

if [ "$(git -C "$DEST" rev-parse -q --verify HEAD 2>/dev/null || true)" != "$OPENGOTHIC_COMMIT" ]; then
    git -C "$DEST" fetch -q --depth 1 origin "$OPENGOTHIC_COMMIT"
fi

# Всегда с чистого состояния: локальные правки в third_party не сохраняются, только патчи.
# Каталог сборки (build/) сохраняется, чтобы повторная сборка была быстрой.
git -C "$DEST" reset -q --hard
git -C "$DEST" checkout -q --force "$OPENGOTHIC_COMMIT"
git -C "$DEST" clean -q -fd -e build/
git -C "$DEST" submodule update -q --init --recursive --depth 1 --force
git -C "$DEST/lib/Tempest" checkout -q --force .
git -C "$DEST/lib/Tempest" clean -q -fd
git -C "$DEST" apply --whitespace=nowarn "$PATCH"
git -C "$DEST/lib/Tempest" apply --whitespace=nowarn "$PATCH_TEMPEST"
echo "OpenGothic $OPENGOTHIC_COMMIT готов: $DEST"
