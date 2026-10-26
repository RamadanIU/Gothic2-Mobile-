# Прежняя версия: эмуляция Windows (Winlator)

До 2026-10-21 приложение запускало **оригинальный `Gothic2.exe`** через встроенный Winlator 11.2:
Wine WoW64 + Box64 (x86 → ARM64) + графика D7VK/Turnip или WineD3D, X-сервер Winlator, наш сенсорный слой
поверх него (Kotlin: модули `:app`, `:core`, `:winlator`). Игра работала (30 FPS на Adreno 740), но оставались
мерцание в D7VK, слабые телефоны (Mali) не тянули, а управление было «вслепую»: приложение не видело игру.

**Решение (2026-10-21, пользователь):** основной проект — движок OpenGothic, эмуляция Windows больше не нужна.
Старый код убран из сборки, но не потерян — он весь в истории git.

## Где лежит

- Последний коммит с Winlator в `main`: **`b3f4769`** (Merge PR #31). Всё дерево той версии:
  `git checkout b3f4769` (или `git worktree add ../g2-winlator b3f4769`).
- Вернуть отдельные части в текущую ветку: `git checkout b3f4769 -- app core winlator third_party/winlator.patch tools/fetch-winlator.sh`
  (и вернуть `include(":core", ":winlator")` в `settings.gradle.kts`, зависимости — из `gradle/libs.versions.toml` той версии).
- Журнал всех решений и проверок той версии — `docs/PROGRESS.md` (разделы до «2026-10-21»).

## Что из неё может пригодиться

- `core/gamedata/VariantLayout` — запуск классической Gothic II без аддона (прячет архивы аддона, подменяет `Paths.d`);
  у OpenGothic классика — отдельная задача.
- `core/saves` — поколения резервных копий сохранений с SHA-256, ZIP-импорт/экспорт.
- `app/storage/StoragePaths`, `LogLocation` — пути SAF → файловая система, журнал в «Загрузках».
- `core/gamedata/BuildFingerprint` — отчёт о сборке (SHA-256 файлов копии игрока).
- Разбор проблем Winlator на Adreno/Mali (D7VK `GetCaps`, гонка копирования кадра X-сервера, `TU_DEBUG`) —
  `docs/PROGRESS.md`, разделы 2026-10-14…18.
