# Архитектура

```text
Android-приложение (app)
├── LauncherActivity      первый запуск: SAF-выбор папки → BuildVerifier → игра прямо из папки
│                         (StoragePaths → путь, разрешение «Файлы», GameFolder) или копия
│                         (GameImporter); далее «Играть»: GameLauncher.prepare → GameActivity.
│                         Одна игра на процесс: после игры процесс завершается/перезапускается
├── GameActivity          наследник XServerDisplayActivity (Winlator) + TouchOverlayView поверх
│   ├── TouchOverlayView  рисует кнопки, передаёт касания в TouchRouter
│   ├── GamepadMapper     геймпад → тот же InputArbiter
│   └── XServerInputSink  скан-коды DIK → коды X, мышь → injectPointerMoveDelta
├── GameLauncher          установка Wine (rootfs), контейнер «Gothic II» (D: = папка игры),
│                         GOTHIC.INI, файлы варианта (VariantLayout)
├── SettingsActivity      игровые настройки, сохранения, диагностика (вместе с журналом Wine)
└── WinlatorBackend       RuntimeBackend поверх Winlator (стоп, ввод)

winlator (Winlator 11.2, LGPL-2.1; исходники — tools/fetch-winlator.sh + third_party/winlator.patch)
└── X-сервер на Java, Wine WoW64 + Box64 (rootfs), Vortek/Turnip, DXVK/D7VK/WineD3D, ALSA/PulseAudio

core (без Android, покрыт тестами)
├── input     InputArbiter ← TouchRouter ← OverlayLayout; KeyStateTracker → InputSink
├── bridge    BridgeSession (рукопожатие, токен, таймаут) → ModeController → InputArbiter.setMode;
│             BoundedLineReader — чтение строк с ограничением длины
├── diagnostics LatencyStats, TimedInputSink (задержка ввода), SessionJournal (нештатное завершение),
│             GameExit (выход игрока или сбой — по выводу Wine)
├── saves     SaveManager (копии, восстановление, ZIP), SaveBackupPolicy (когда копировать)
├── gamedata  GameTree, SupportedBuild, BuildVerifier, GameImporter (копия), GameFolder (игра из папки)
├── config    IniFile, GothicIniWriter, DeviceProfile(s), UserSettings
└── runtime   RuntimeComponents, ComponentInstaller + TarExtractor, LaunchSpecBuilder, RuntimeBackend,
              SessionController, RendererSetup (сторонний ddraw.dll/GD3D11 → обёртка или WineD3D)
```

Соответствие модулям раздела 4 плана:

| План | Реализация |
|---|---|
| `GameActivity` | `app/ui/GameActivity.kt` |
| `RuntimeManager` | `app/runtime/GameLauncher.kt` + модуль `:winlator`; `core/runtime/*` — общие типы |
| `DeviceProfile` | `core/config/DeviceProfile.kt` |
| `InputRouter` | `core/input/InputArbiter.kt`, `TouchRouter.kt`, `app/input/GamepadMapper.kt` |
| `TouchOverlay` | `core/input/OverlayLayout.kt`, `app/ui/TouchOverlayView.kt` |
| `GameStateBridge` | `core/bridge/*` (сторона приложения); адаптер в игре — этап 4 |
| `SaveManager` | `core/saves/*` |
| `Diagnostics` | `app/storage/Diagnostics.kt` |

## Поток ввода

```text
касание → TouchOverlayView → TouchRouter (владение пальцем) → InputArbiter (приоритеты, фазы)
       → KeyStateTracker (удержание по источникам, отпускания раньше нажатий) → InputSink → игра
```

- `InputArbiter` — единственное место, где решается, какие клавиши зажаты. Удар — фазы
  «снять движение → действие → действие+направление → отпустить → пауза», длительности в
  `CombatTiming` подбираются на 30/60 FPS. Блок вытесняет удар. Удары не ставятся в очередь.
- Время передаётся через `clock`; `nextDeadline()` говорит UI-потоку, когда вызвать `tick()`.
  Частота оверлея не привязана к частоте рендера игры.
- Скан-коды DirectInput задаются `KeyBindings`; значения по умолчанию проверяются на сборке.

## Режимы управления

`ControlMode` задаёт видимые панели и допустимые сигналы. Источник режима — `ModeController`:
адаптер (уровень 2), если сеанс моста активен, иначе догадка (уровень 1). Арбитр сообщает о каждом
одиночном нажатии (`InputArbiter.onKeyTapped`), и по нему `ModeController` ведёт состояние: старт
игры — главное меню; Tab — инвентарь; Esc в мире — меню игры (Enter — уровень глубже, Esc — назад);
M/L/B — экран карты/журнала/персонажа; Space и слоты 1–0 — что в руках (меч/лук/магия). Если игрок
тянет камеру на экране меню или диалога (`TouchRouter.onWorldIntent`), меню уже закрыто — панель
возвращается к игре. Диалог и торговлю начинает игра — без адаптера их не видно: стрелки меню и
так совпадают с ходьбой, главная кнопка нажимает «действие», а полная панель меню включается
пунктом «Ещё» → «Кнопки меню и диалога».

Раскладка (`OverlayLayout.gothicDefault`): «Сумка», «Ещё», Esc («Меню»/«Назад») сверху; главная
кнопка (`TouchCommand.Main`), прыжок, оружие с колесом слотов (`TouchCommand.Weapon`), в бою блок;
в меню — крестовина (`TouchCommand.DPad`). Главная кнопка в мире — жест арбитра
`strikeBegin/strikeAim/strikeEnd`: палец на кнопке держит «действие» (прицел), свайп влево/вправо —
боковой удар, вверх/вниз и держать — удар вперёд/блок, отпускание без свайпа — «действие + вперёд»
(взаимодействие, удар, выстрел, заклинание). Слой панели (`ControlMode.layer`) меняется — удержания
снимаются; внутри слоя (мир ↔ оружие) — сохраняются.
Любая ошибка протокола, неверный токен, чужой EXE или молчание > 3 с закрывают сеанс и
возвращают универсальную панель.

## Мост с адаптером: транспорт

`app/bridge/BridgeServer` создаёт Unix-сокет `files/bridge/adapter.sock` (FILESYSTEM namespace —
недоступен другим приложениям, в отличие от абстрактного) и передаёт путь и одноразовый токен
игре через `G2M_BRIDGE_SOCKET` / `G2M_BRIDGE_TOKEN`. Каждое подключение — новый `BridgeSession`;
приложение раз в секунду шлёт `PING` и закрывает сеанс, если адаптер молчит дольше 3 с.
Что адаптер внутри Wine подключается к Unix-сокету (AF_UNIX в winsock Wine) — проверяется на этапе 4.

## Хранение

| Каталог | Содержимое |
|---|---|
| `files/game` | импортированная игра, включая `Saves` |
| `files/save-backups/<слот>/<время>` | поколения копий с манифестом SHA-256 |
| `<dataDir>/r` | rootfs Winlator: Wine, Box64, драйверы; `home/xuser-N/.wine` — префикс контейнера |
| `files/session.marker` | отметка запущенного сеанса (нештатное завершение) |
| `files/logs` | журнал диагностики с ротацией; `wine.log` — вывод Wine/Box64 последнего запуска |

Импорт игры и замена слотов идут через временный каталог и атомарное переименование;
`GameImporter.recoverInterrupted` возвращает установку и сохранения, если процесс погиб посередине.

## Среда исполнения: Winlator внутри приложения

Основа — исходники Winlator 11.2 (`brunodev85/winlator-app`, коммит в `tools/fetch-winlator.sh`),
собранные модулем-библиотекой `:winlator`. В репозитории хранится только наш патч
`third_party/winlator.patch`; сами исходники (≈300 МБ вместе с rootfs) скачивает скрипт.

Что меняет патч:
- **Путь rootfs.** Программы внутри rootfs (интерпретатор ELF, `ld.so.cache`, fontconfig, libxcb…)
  собраны с путём `/data/data/com.winlator/files/rootfs` (36 символов). При распаковке любых
  `.tzst` (`TarCompressorUtils` → `PathRelocator`) он заменяется строкой той же длины, ведущей
  в `<dataDir>/r` нашего пакета: `/data/data/gothic2.mobile.dev//////r` (лишние «/» не меняют путь).
  Поэтому имя пакета не длиннее 23 символов. Нативный код Winlator получает имя пакета через CMake
  (`APP_PACKAGE_NAME`, из `gradle.properties`).
- **Архивы среды не в APK.** Все `*.tzst` Winlator (≈140 МБ: rootfs, драйверы, DXVK…) исключены
  из APK (`ignoreAssetsPattern` в `:app`), иначе APK (≈170 МБ) не передать пользователю. При сборке
  `:winlator` пишет `assets/remote_assets.txt`: адрес raw.githubusercontent.com закреплённого коммита
  и для каждого архива размер и SHA-256. `RemoteAssets` скачивает недостающее (проверка размера и
  SHA-256, атомарная запись, 3 попытки) в `files/remote-assets`; `GameLauncher` заранее качает всё
  нужное с общим прогрессом, остальное Winlator докачает сам при обращении. APK ≈ 24 МБ.
- **Без интерфейса Winlator.** Нет значка лаунчера; `RootFSInstaller.installBlocking` ставит
  системные файлы без его экранов; `ContainerManager.createContainer` открыт для фонового потока.
- **Хуки активити.** `XServerDisplayActivity`: `onBeforeStart` (не запускать после выгрузки
  процесса), `onSetupLogging` (журнал Wine, `WINEDEBUG`, аргументы EXE через `EXTRA_EXEC_ARGS`,
  переменные моста), `exit()`/`stopEnvironment()` (возврат в наше приложение, а не в Winlator).
- **Сборка как библиотеки:** `switch` по `R.id` переписаны на имена ресурсов (`ResNames`),
  атрибуты тем — из `androidx.appcompat.R`/`material.R`, `FileProvider` — `${applicationId}`.

Почему `targetSdk 28`: Wine, Box64 и их `.so` исполняются из каталога данных приложения, а для
`targetSdk ≥ 29` Android это запрещает (так же сделано в Winlator и Termux). Приложение
распространяется не через Google Play. `.so` распаковываются (`useLegacyPackaging`): PulseAudio
запускается как программа.

Запуск: `GameLauncher.prepare` (фоновый поток) → `GameActivity` с `container_id`, `exec_path`
(Linux-путь EXE) и аргументами. Графика по профилю: `WINED3D` → `dxwrapper=wined3d`
(ddraw Wine поверх OpenGL), `D3D7_TO_D3D9_DXVK` → DXVK с `ddrawWrapper=d7vk` (Direct3D 7 сразу
в Vulkan). Графический драйвер — по умолчанию Winlator (Vortek + Gladio).
