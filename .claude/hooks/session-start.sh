#!/bin/bash
# Начало облачной сессии: рабочая ветка сессии сразу ставится на свежий origin/main, а Claude получает
# короткую справку. Иначе сессия могла начаться со старой копии репозитория (ещё на Winlator) — Claude
# работал по устаревшему CLAUDE.md и каждый раз сообщал пользователю, что «ветка устарела».
set -uo pipefail

[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

note() { echo "[session-start] $*"; }

fetched=0
for delay in 0 2 4 8; do
    sleep "$delay"
    if git fetch -q origin main 2>/dev/null; then fetched=1; break; fi
done
if [ "$fetched" != 1 ]; then
    note "не удалось получить origin/main — перед работой сделать: git fetch origin main && git merge origin/main"
    exit 0
fi

# Неполная копия (git clone --depth): номер версии APK считается по числу коммитов - догрузить всю историю, иначе APK
# получит номер меньше установленного и не встанет поверх («Приложение не установлено»)
if [ "$(git rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]; then
    for delay in 0 2 4 8; do
        sleep "$delay"
        if git fetch -q --unshallow origin 2>/dev/null; then
            note "догружена полная история git: номер версии APK - по всем коммитам"
            break
        fi
    done
    [ "$(git rev-parse --is-shallow-repository 2>/dev/null)" = "true" ] && \
        note "история git неполная - перед сборкой APK: git fetch --unshallow origin (иначе APK не встанет поверх)"
fi

branch="$(git rev-parse --abbrev-ref HEAD)"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    note "в ветке $branch есть незакоммиченные правки — с origin/main не синхронизировано, сделать это вручную (merge)"
elif [ -z "$(git rev-list origin/main..HEAD)" ]; then
    # Своих коммитов нет (или все уже в main): просто встать на свежий main.
    if [ "$branch" = "main" ]; then
        git merge -q --ff-only origin/main
    else
        git checkout -q -B "$branch" origin/main
    fi
    note "ветка $branch поставлена на свежий origin/main ($(git log -1 --format='%h %s' origin/main))"
elif git merge -q --no-edit origin/main >/dev/null 2>&1; then
    note "в ветку $branch влит свежий origin/main (свои коммиты ветки сохранены)"
else
    git merge --abort 2>/dev/null
    note "в ветке $branch свои коммиты конфликтуют с origin/main — разрешить конфликт merge'ем до начала работы"
fi

note "Проект — на движке OpenGothic (Winlator/D7VK убраны 2026-10-21). Актуальный контекст — CLAUDE.md из этой ветки;" \
     "если в памяти сессии другой CLAUDE.md — верить файлу. Пользователю об устаревшей ветке не сообщать: синхронизация уже сделана."
exit 0
