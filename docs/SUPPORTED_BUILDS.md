# Закрепление сборки Gothic II Classic

Приложение принимает сборку только по SHA-256 исполняемого файла. Описание —
`app/src/main/assets/supported_builds.ini`:

```ini
[build:g2-classic-ru]
title=Gothic II Classic (рус.)
exe=System/Gothic2.exe
exeSha256=PENDING          ; ← SHA-256 эталонного EXE после этапа 1
language=ru
requires=Data/Worlds.vdf,Data/Textures.vdf,Data/Meshes.vdf,Data/Anims.vdf
requiresSha256=            ; необязательно: путь:sha256 для ключевых архивов
saves=Saves
ini=System/GOTHIC.INI
args=
```

Пока `exeSha256=PENDING`, сборка «незакреплённая»: её принимает только режим разработчика
(включён по умолчанию в debug-сборке). Release-сборка незакреплённые копии отклоняет.

## Как закрепить

1. Получить легальную копию и определить способ запуска именно Classic (не Gold/NoTR).
2. `sha256sum System/Gothic2.exe` и ключевых архивов `Data/*.vdf`.
3. Пройти критерий этапа 1 (docs/ROADMAP.md).
4. Записать значения в манифест; добавить отдельную секцию для каждой языковой поставки.
5. Названия проверяются без учёта регистра: `SYSTEM/GOTHIC2.EXE` и `System/Gothic2.exe` эквивалентны.

Наличие файлов `*Addon*.vdf` не отклоняет сборку само по себе (Gold содержит обе версии), но
используется в сообщении пользователю, если EXE не опознан.
