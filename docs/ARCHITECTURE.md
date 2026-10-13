# Архитектура

```text
Android-приложение (app)
├── LauncherActivity      первый запуск: SAF-выбор папки → BuildVerifier → GameImporter
├── GameActivity          полноэкранный вывод + TouchOverlayView, жизненный цикл, геймпад
│   ├── TouchOverlayView  рисует кнопки, передаёт касания в TouchRouter
│   ├── GamepadMapper     геймпад → тот же InputArbiter
│   └── GameLauncher      GOTHIC.INI + LaunchSpec → RuntimeBackend
├── SettingsActivity      игровые настройки, сохранения, диагностика
└── RuntimeBackend        граница со средой исполнения (сейчас StubRuntimeBackend)

core (без Android, покрыт тестами)
├── input     InputArbiter ← TouchRouter ← OverlayLayout; KeyStateTracker → InputSink
├── bridge    BridgeSession (рукопожатие, токен, таймаут) → ModeController → InputArbiter.setMode;
│             BoundedLineReader — чтение строк с ограничением длины
├── diagnostics LatencyStats, TimedInputSink (задержка ввода), SessionJournal (нештатное завершение)
├── saves     SaveManager (копии, восстановление, ZIP), SaveBackupPolicy (когда копировать)
├── gamedata  GameTree, SupportedBuild, BuildVerifier, GameImporter
├── config    IniFile, GothicIniWriter, DeviceProfile(s), UserSettings
└── runtime   RuntimeComponents, ComponentInstaller + TarExtractor, LaunchSpecBuilder, RuntimeBackend,
              SessionController
```

Соответствие модулям раздела 4 плана:

| План | Реализация |
|---|---|
| `GameActivity` | `app/ui/GameActivity.kt` |
| `RuntimeManager` | `core/runtime/*` + `app/runtime/GameLauncher.kt`; реальный backend — этап 2 |
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
адаптер (уровень 2), если сеанс моста активен, иначе ручные кнопки «Бой / Мир / Навигация».
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
| `files/runtime/prefix` | префикс Wine |
| `files/runtime/components/<id>` | распакованные компоненты среды с маркером версии |
| `files/session.marker` | отметка запущенного сеанса (нештатное завершение) |
| `files/logs` | журнал диагностики с ротацией |
| `cache/shaders` | кэш шейдеров DXVK (можно удалять) |

Импорт игры и замена слотов идут через временный каталог и атомарное переименование;
`GameImporter.recoverInterrupted` возвращает установку и сохранения, если процесс погиб посередине.

## Подключение реальной среды исполнения (этап 2)

Нужно реализовать `RuntimeBackend` поверх выбранной основы класса Winlator:

1. `prepare` — распаковать закреплённые компоненты из `runtime_components.ini` с проверкой SHA-256,
   создать префикс, выполнить `LaunchSpec.prefixSetup` (версия Windows).
2. `launch` — запустить `LaunchSpec.windowsExePath` с `env`, без desktop shell.
3. `attachSurface` — направить вывод X-сервера/графики на `Surface` из `GameActivity`.
4. `inputSink` — доставлять скан-коды и относительную мышь тем путём, который реально получает
   Gothic под Wine (проверяется на этапе 1).
5. `pause/resume` — проверенный способ паузы; `Esc` для этого не используется.
