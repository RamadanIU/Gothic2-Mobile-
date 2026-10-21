#!/usr/bin/env bash
# Собирает пробную APK на OpenGothic (arm64-v8a): build/og/gothic2-og.apk.
# Перед первой сборкой: tools/fetch-opengothic.sh и окружение из CLAUDE.md («Пробная сборка
# на OpenGothic»): Android SDK с NDK 27.0.12077973 и CMake 3.22.1, Gradle, glslangValidator,
# заголовки Vulkan не старше движка (VULKAN_SDK — каталог с include/vulkan).
# Аргументы передаются Gradle; при 429 от Maven Central:
#   tools/build-opengothic.sh --init-script "$PWD/tools/og-maven-mirror.gradle"
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/opengothic"
BUILD="$SRC/build/g2m"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export VULKAN_SDK="${VULKAN_SDK:-/opt/vulkan-sdk}"
NDK="$ANDROID_HOME/ndk/27.0.12077973"

[ -d "$SRC/.git" ] || { echo "Нет исходников: запусти tools/fetch-opengothic.sh" >&2; exit 1; }
[ -f "$VULKAN_SDK/include/vulkan/vulkan_core.h" ] || { echo "Нет заголовков Vulkan в $VULKAN_SDK/include" >&2; exit 1; }
command -v glslangValidator >/dev/null || { echo "Нет glslangValidator" >&2; exit 1; }
command -v gradle >/dev/null || { echo "Нет gradle" >&2; exit 1; }

# Номер версии растёт с каждым коммитом: новая APK ставится поверх старой
VERSION_CODE="$(git -C "$ROOT" rev-list --count HEAD)"
VERSION_NAME="0.1.$VERSION_CODE"

cmake -S "$SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release \
    -DTEMPEST_ANDROID_TARGET_SDK=28 \
    -DG2M_ANDROID_DIR="$ROOT/og-android" \
    -DG2M_VERSION_CODE="$VERSION_CODE" -DG2M_VERSION_NAME="$VERSION_NAME" >/dev/null

# Тот же общий debug-ключ, что у основного приложения (не секретный)
export TEMPEST_KEYSTORE="$ROOT/app/debug.keystore"
export TEMPEST_KEY_ALIAS=androiddebugkey
export TEMPEST_STORE_PASSWORD=android
export TEMPEST_KEY_PASSWORD=android

# Свой settings.gradle: иначе Gradle считает проект частью основной сборки репозитория
echo "rootProject.name = 'gothic2-og'" > "$BUILD/OpenGothic-apk/settings.gradle"

gradle -p "$BUILD/OpenGothic-apk" --no-daemon --max-workers=4 -q \
    "-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8" "$@" assembleRelease

mkdir -p "$ROOT/build/og"
cp "$BUILD/OpenGothic-apk/build/outputs/apk/release/gothic2-og-release.apk" "$ROOT/build/og/gothic2-og.apk"
ls -l "$ROOT/build/og/gothic2-og.apk"
