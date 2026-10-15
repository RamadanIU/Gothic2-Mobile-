#!/usr/bin/env bash
# Скачивает исходники Winlator (LGPL-2.1) закреплённой версии и накладывает наш патч.
# Результат — third_party/winlator-app (в .gitignore): из него собирается модуль :winlator.
#
# Запуск один раз после клонирования репозитория и после смены WINLATOR_COMMIT:
#   tools/fetch-winlator.sh
set -euo pipefail

WINLATOR_REPO="https://github.com/brunodev85/winlator-app.git"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Winlator 11.2 (2026-09-25); коммит задан в gradle.properties (winlator.commit).
WINLATOR_COMMIT="$(sed -n 's/^winlator\.commit=//p' "$ROOT/gradle.properties")"
[ -n "$WINLATOR_COMMIT" ] || { echo "Нет winlator.commit в gradle.properties" >&2; exit 1; }
DEST="$ROOT/third_party/winlator-app"
PATCH="$ROOT/third_party/winlator.patch"

if [ ! -d "$DEST/.git" ]; then
    mkdir -p "$DEST"
    git -C "$DEST" init -q
    git -C "$DEST" remote add origin "$WINLATOR_REPO"
fi

if [ "$(git -C "$DEST" rev-parse -q --verify HEAD 2>/dev/null || true)" != "$WINLATOR_COMMIT" ]; then
    git -C "$DEST" fetch -q --depth 1 origin "$WINLATOR_COMMIT"
fi

# Всегда с чистого состояния: локальные правки в third_party не сохраняются, только патч.
git -C "$DEST" checkout -q --force "$WINLATOR_COMMIT"
git -C "$DEST" clean -q -fdx
git -C "$DEST" apply --whitespace=nowarn "$PATCH"
echo "Winlator $WINLATOR_COMMIT готов: $DEST"
