#!/usr/bin/env bash
# Собирает D7VK 1.11 (zlib) с нашим патчем third_party/d7vk.patch и кладёт ddraw.dll в assets
# приложения (app/src/main/assets/d7vk/). Приложение подменяет им ddraw.dll из Winlator 11.2
# в режимах D7VK (D7vkBuild в GameActivity). Готовый файл хранится в репозитории: пересобирать
# нужно только после правки патча, затем обновить D7vkBuild.SHA256 (скрипт печатает новую сумму).
#
# Нужны (Ubuntu): g++-mingw-w64-i686-posix glslang-tools meson ninja-build
#   apt-get install -y --no-install-recommends g++-mingw-w64-i686-posix glslang-tools meson
set -euo pipefail

D7VK_REPO="https://github.com/WinterSnowfall/d7vk.git"
# Тег v1.11 — та же версия, что в Winlator 11.2 (dxwrapper/d7vk-1.11.tzst).
D7VK_COMMIT="6905c8869b863721e882e0e7c54b3cbee1503ffe"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PATCH="$ROOT/third_party/d7vk.patch"
OUT="$ROOT/app/src/main/assets/d7vk/ddraw.dll"
WORK="${D7VK_WORK:-${TMPDIR:-/tmp}/d7vk-build}"
SRC="$WORK/src"
BUILD="$WORK/build"

if [ ! -d "$SRC/.git" ]; then
    git clone -q --branch v1.11 --recurse-submodules --shallow-submodules "$D7VK_REPO" "$SRC"
fi
[ "$(git -C "$SRC" rev-parse HEAD)" = "$D7VK_COMMIT" ] || { echo "D7VK: не тот коммит" >&2; exit 1; }
git -C "$SRC" checkout -q --force "$D7VK_COMMIT"
git -C "$SRC" clean -q -fdx
git -C "$SRC" apply "$PATCH"

# Без отметки времени и путей сборки в файле: одна и та же сборка даёт тот же SHA-256.
# Отладочные символы снимает компоновщик (-s): strip из binutils вписал бы в PE текущее время.
rm -rf "$BUILD"
(cd "$SRC" && meson setup --cross-file build-win32.txt \
    --buildtype release \
    -Db_ndebug=if-release -Dbuild_id=false \
    -Denable_dxgi=false -Denable_d3d8=false -Denable_d3d10=false -Denable_d3d11=false \
    -Dc_args="-ffile-prefix-map=$SRC=." -Dcpp_args="-ffile-prefix-map=$SRC=." \
    -Dc_link_args=-Wl,--no-insert-timestamp,-s -Dcpp_link_args=-Wl,--no-insert-timestamp,-s \
    "$BUILD" >/dev/null)
ninja -C "$BUILD" src/ddraw/ddraw.dll >/dev/null

mkdir -p "$(dirname "$OUT")"
cp "$BUILD/src/ddraw/ddraw.dll" "$OUT"
echo "$OUT"
sha256sum "$OUT"
