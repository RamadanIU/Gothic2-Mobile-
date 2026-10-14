# CLAUDE.md — контекст проекта для новой сессии

Этот файл читается автоматически в начале каждой сессии Claude Code. Здесь — всё, чтобы
продолжить работу с нуля. Подробности: `docs/PROGRESS.md` (журнал решений и сделанного),
`docs/ROADMAP.md` (этапы с чекбоксами), `docs/PLAN.md` (принятые решения по разделам плана).

## Что за проект

Android-приложение «Gothic II для Android»: запускает **лицензионную Gothic II** локально на телефоне
(без root, без облака) через встроенную среду класса Winlator (Wine WoW64 + Box64 + графика),
которую пользователь не видит. Снаружи — только игра и сенсорное управление, сделанное под Gothic.

- **Цель — Gothic II: Золотое издание** (Gold) с двумя вариантами запуска: «Ночь Ворона» и
  классическая Gothic II без дополнения. Отдельная Classic больше не продаётся; её дисковая версия
  поддерживается как дополнительная сборка (`g2-classic-ru`).
- У владельца репозитория есть **купленная оригинальная Gold-копия из GOG** (офлайн-установщик
  `setup.exe` + 3 `.bin`, ≈2,6 ГБ). В чат её не передать. Перед импортом установщик нужно распаковать
  в папку с `System` и `Data` (способы — docs/SUPPORTED_BUILDS.md).
- **Пиратские копии не используем** (ни для тестов, ни для релиза): не скачивать, не предлагать,
  не поддерживать NoCD/взломанные EXE. Сборка закрепляется по **отчёту о сборке** — текстовому файлу
  с SHA-256, который пользователь формирует на телефоне из своей копии (см. ниже).
- Язык общения и документации — **русский**.

## Текущее состояние (на 2026-10-14)

**Сборка игры пользователя закреплена:** `g2-gold-gog` в `app/src/main/assets/supported_builds.ini`
(GOG «Gold Classic» от THQ Nordic с GD3D11, Union, SystemPack, DSOAL, `Gothic2c.exe` — подробности в
docs/SUPPORTED_BUILDS.md). Язык — русский. Включены оба варианта: «Ночь Ворона» (`-game:GothicGame.ini`)
и классика (`Gothic2c.exe` без аргументов; как `Gothic2c.bat`, на время игры прячет архивы аддона и
подменяет `Paths.d` — ключ `rename=` и `VariantLayout` с откатом после сбоя; сохранения — `Saves_GII`).


**Среда исполнения — Winlator 11.2, встроенный в приложение** (модуль `:winlator`, docs/ARCHITECTURE.md,
раздел «Среда исполнения»). «Играть» → при первом запуске загрузка файлов Wine с GitHub (≈100 МБ,
SHA-256) и установка (1–3 мин) → контейнер
«Gothic II» с диском D: = папка игры → экран игры (наследник `XServerDisplayActivity`) с нашим
сенсорным управлением поверх. На телефоне ещё не запускалось — ждём результат от пользователя.

Готово и покрыто тестами (97 тестов в `core`, 3 в `app`), lint без ошибок, debug/release APK собираются:
- ввод: единый арбитр (`InputArbiter`), джойстик с гистерезисом, камера, направленные удары и блок,
  распределение пальцев (`TouchRouter`), раскладка и её редактор, радиальное меню слотов, геймпад;
- сохранения: поколения копий с SHA-256, восстановление, ZIP импорт/экспорт, копия только после
  подтверждённого завершения записи; раздельно по вариантам игры;
- игра: проверка сборки по SHA-256 EXE (`BuildVerifier`), атомарный импорт с откатом (`GameImporter`),
  варианты запуска (`GameVariant`), **отчёт о сборке** (`BuildFingerprint`);
- среда: Winlator (`GameLauncher`, `GameActivity`, `XServerInputSink`, `WinlatorBackend`), замена пути
  rootfs при распаковке (`PathRelocator`), профили устройств → WineD3D или D7VK, `GOTHIC.INI`;
  журнал Wine/Box64 (`files/logs/wine.log`) входит в экспорт диагностики;
- мост с игровым адаптером: протокол, рукопожатие с токеном, Unix-сокет в приватном каталоге;
- Android: импорт через SAF, экран игры, вывод 4:3 с полями, экран восстановления, перегрев, настройки.

**Не готово (главное):**
1. **Игра ещё ни разу не запускалась на телефоне** — первый APK со встроенным Winlator отправлен пользователю.
2. Не подтверждены в игре: клавиши (`KeyBindings`), ключи `GothicIniWriter.Keys`, доставка мыши
   (`injectPointerMoveDelta`), длительности ударов (`CombatTiming`), выбор графики (WineD3D/D7VK).
3. Игровой адаптер внутри процесса Gothic (C++) не написан.
4. `runtime_components.ini`, `ComponentInstaller`, `LaunchSpecBuilder`, `RendererSetup` в `core` остались от
   прежней схемы и при запуске не используются — убрать или переиспользовать после первого запуска.

## Следующие действия (по порядку)

1. **Первый запуск на телефоне пользователя.** Пользователь не настраивает Winlator сам: ставит APK,
   жмёт «Играть», при проблеме — Настройки → экспорт журнала диагностики (в нём и вывод Wine).
   По журналу чинить: путь rootfs, драйвер (Vortek/Turnip), ddraw (WineD3D/D7VK), звук, ввод.
2. После запуска — этап 1 по docs/TEST_PLAN.md: клавиши, мышь-камера, удары, сохранение/загрузка,
   классика; 30–60 минут игры.
3. Игровой адаптер (этап 4) и стабилизация (этап 5).

Без телефона и игры можно продолжать только то, что не требует запуска: тесты, UI, документацию.

## Сборка в облачной сессии

В контейнере нет Android SDK — установить (≈2 мин):

```bash
mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp && \
curl -sS -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
unzip -q cmdtools.zip -d /opt/android-sdk/tmp && mv /opt/android-sdk/tmp/cmdline-tools /opt/android-sdk/cmdline-tools/latest && \
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk "platforms;android-35" "build-tools;35.0.0" "platform-tools" >/dev/null
echo "sdk.dir=/opt/android-sdk" > local.properties   # файл в .gitignore
```

Ещё нужны NDK и CMake (нативный код Winlator) и исходники Winlator:

```bash
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk "ndk;24.0.8215888" "cmake;3.22.1" >/dev/null
tools/fetch-winlator.sh   # клонирует закреплённый коммит в third_party/winlator-app и накладывает патч
```

Проверка перед каждым пушем:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

- Правки Winlator делаются в `third_party/winlator-app`, затем сохраняются в патч (иначе пропадут):
  `cd third_party/winlator-app && git add -A -N && git diff --binary > ../winlator.patch`.
  Каждую правку помечать комментарием «Gothic II Mobile:».
- `.claude/settings.json` разрешает `./gradlew` и `tools/fetch-winlator.sh` без вопросов.
- APK ≈ 24 МБ: архивы Wine (`*.tzst`) в APK не кладутся, приложение их скачивает (`RemoteAssets`).
  Лимит отправки файла пользователю здесь — 30 МБ. После `ignoreAssetsPattern`/крупных изменений
  собирать APK начисто (удалить `app/build/outputs/apk`): инкрементальная упаковка оставляет «дыры».
  Debug подписывается общим ключом `app/debug.keystore` — новый APK ставится поверх старого.

- Maven Central иногда отвечает **429** — просто повторить команду через 15–30 с.
- Эмулятор в контейнере не работает (нет KVM) — на устройстве проверяет только пользователь.
- GitHub Actions в репозитории не получает раннер (ограничение аккаунта): CI в PR красный не из-за кода.
- Debug-APK для пользователя: `app/build/outputs/apk/debug/app-debug.apk` (только arm64-v8a).

## Правила кода (выучено на ошибках)

- `core` — чистый Kotlin/JVM, но работает **на Android (minSdk 29)**: не использовать API Java 11+
  (`Files.readString/writeString`, `InputStream.transferTo`, `Path.of`). Вместо них — `kotlin.io.path`
  (`readText`, `writeText`, `appendText`). Lint проверяет и `core` (`checkDependencies = true`).
- Внутри `View.apply { }` имя `overlay` перекрывается `View.getOverlay()` — поле оверлея в
  `GameActivity` называется `touchOverlay`; не называть поля именами свойств `View`.
- Тесты `core` запускаются с `LC_ALL=C.UTF-8` (нелатинские имена файлов) — уже в `core/build.gradle.kts`.
- Импорты сортированы: сначала проектные/androidx, затем `java.*`, `javax.*`, `kotlin.*`.
- Комментарии и строки интерфейса — по-русски, в стиле окружающего кода; ссылки на план — «раздел N плана».
- Любой внешний ввод (архивы, ZIP сохранений, строки моста, папка игры) считается недоверенным:
  проверка путей (`FileOps.isSafeRelative`), лимиты размеров, атомарная запись через временный каталог.
- Никаких игровых файлов в репозитории (`.gitignore` исключает `*.vdf`, `*.mod`, `game-data/`).
- Имя пакета приложения (`gradle.properties`, `gothic2.applicationId` + суффикс debug) не длиннее
  23 символов: из него строится путь rootfs той же длины, что у Winlator.

## Git и PR

- Рабочая ветка сессии задаётся в инструкции сессии. Пользователь **быстро мёржит PR** — перед новой
  работой проверить, смёрджен ли последний PR, и если да, начать ветку заново от `origin/main`
  (`git fetch origin main && git checkout -B <ветка> origin/main`). Новая работа — новый PR.
- Force-push запрещён правилами окружения: историю не переписывать, для синхронизации — merge.
- PR создавать, когда пользователь просит («Pr»). Описание — по-русски: «Что сделано» / «Проверка».
- **После каждого пуша проверять, что изменения в открытом PR.** Если прошлый PR уже смёржен
  (пользователь мёржит быстро, иногда до последних коммитов), сразу создать новый PR, не дожидаясь
  просьбы: `git fetch origin main && git merge origin/main`, пуш, `create_pull_request`. Пользователь
  просил не забывать об этом.
