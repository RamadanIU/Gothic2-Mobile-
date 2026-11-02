#!/usr/bin/env bash
# Gothic II Mobile: стенд режима стратегии (docs/RTS.md, «Как проверяем») - ядро common/game/rts без движка и без
# игры. Запуск:
#   tools/fetch-opengothic.sh                 # если third_party/opengothic ещё нет
#   tools/rts-test/build.sh [каталог сборки]  # по умолчанию /tmp/rts-test
#   /tmp/rts-test/rtstest                     # проверки, в конце «ALL OK»
#   /tmp/rts-test/rtstest sweep 50 60         # 50 партий ИИ против ИИ по 60 минут: сводка
#   /tmp/rts-test/rtstest game 7 40           # одна партия с донесениями
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
O="$(cd "$HERE/../../third_party/opengothic" && pwd)"
OUT="${1:-/tmp/rts-test}"
mkdir -p "$OUT"
R="$O/common/game/rts"
g++ -std=c++20 -O2 -g -Wall -Wextra -Wconversion -Werror -I"$O/common" \
    "$HERE/test.cpp" "$R/rtsdefs.cpp" "$R/rtscore.cpp" "$R/rtsgen.cpp" "$R/rtsai.cpp" "$R/rtsdiplo.cpp" "$R/rtsio.cpp" \
    -o "$OUT/rtstest"
echo "Собрано: $OUT/rtstest"
