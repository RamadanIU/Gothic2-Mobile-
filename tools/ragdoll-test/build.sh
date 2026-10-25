#!/usr/bin/env bash
# Gothic II Mobile: стенд проверки регдолла на компьютере (без телефона и без игры).
# Настоящие common/physics/ragdoll.cpp и Bullet из third_party/opengothic, упрощённые мир и скелет (stubs.cpp),
# скелеты человека и волка по образцу Biped (test.cpp). Запуск:
#   tools/fetch-opengothic.sh            # если third_party/opengothic ещё нет
#   tools/ragdoll-test/build.sh [каталог сборки]   # по умолчанию /tmp/ragdoll-test
#   /tmp/ragdoll-test/ragtest           # в конце «ALL OK»; RAGDOLL_TRACE=1 при сборке - скорости по ходу падения
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
O="$(cd "$HERE/../../third_party/opengothic" && pwd)"
OUT="${1:-/tmp/ragdoll-test}"
mkdir -p "$OUT/bullet"

if [ ! -f "$OUT/bullet/src/BulletDynamics/libBulletDynamics.a" ]; then
    cmake -S "$O/lib/bullet3" -B "$OUT/bullet" -DCMAKE_BUILD_TYPE=Release -DBUILD_BULLET2_DEMOS=OFF -DBUILD_EXTRAS=OFF \
          -DBUILD_UNIT_TESTS=OFF -DBUILD_CPU_DEMOS=OFF -DBUILD_OPENGL3_DEMOS=OFF -DBUILD_BULLET3=OFF -DBUILD_PYBULLET=OFF \
          -DINSTALL_LIBS=OFF -DBUILD_SHARED_LIBS=OFF >/dev/null
    cmake --build "$OUT/bullet" -j8 --target BulletDynamics BulletCollision LinearMath >/dev/null
fi

EXTRA=""
[ -n "${RAGDOLL_TRACE:-}" ] && EXTRA="-DRAGDOLL_TRACE"
g++ -std=c++20 -O2 -g -Wall $EXTRA -I"$O/common" -I"$O/lib/bullet3/src" -I"$O/lib/Tempest/Engine/include" \
    -I"$O/lib/Tempest/Engine" -I"$O/lib/ZenKit/include" \
    "$HERE/test.cpp" "$HERE/stubs.cpp" "$O/common/physics/ragdoll.cpp" "$O/common/graphics/mesh/animmath.cpp" \
    "$O/lib/Tempest/Engine/math/matrix.cpp" "$O/lib/Tempest/Engine/utility/log.cpp" \
    "$OUT/bullet/src/BulletDynamics/libBulletDynamics.a" "$OUT/bullet/src/BulletCollision/libBulletCollision.a" \
    "$OUT/bullet/src/LinearMath/libLinearMath.a" -o "$OUT/ragtest"
echo "Собрано: $OUT/ragtest"
