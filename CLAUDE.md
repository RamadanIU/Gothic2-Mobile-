# CLAUDE.md — контекст проекта для новой сессии

Этот файл читается автоматически в начале каждой сессии Claude Code. Здесь — всё, чтобы продолжить
работу с нуля. Подробности: `docs/ARCHITECTURE.md` (как устроено), `docs/PROGRESS.md` (журнал решений и
сделанного), `docs/ROADMAP.md` (что дальше), `docs/PLAN.md` (принятые решения).

## Что за проект

Android-приложение «Gothic II»: запускает **лицензионную Gothic II игрока** прямо на телефоне (без root,
без облака). Игру исполняет **OpenGothic** — открытый движок Gothic II, написанный заново сообществом
(github.com/Try/OpenGothic, MIT): нативно на ARM64 + Vulkan, читает файлы оригинальной игры. Поверх — наши
патчи движка: сенсорное управление, серии ударов, автобой, напарники, настройки графики, «Взять всё».

- **Решение 2026-10-21 (пользователь):** основной проект — OpenGothic. Прежняя версия на эмуляции Windows
  (Winlator: Wine + Box64 + D7VK, модули `:app`/`:core`/`:winlator` на Kotlin) убрана из сборки и лежит в
  истории git — коммит `b3f4769`, см. docs/LEGACY_WINLATOR.md. Не возвращать её без просьбы пользователя.
- Игра — **Gothic II: Золотое издание / «Ночь Ворона»**, русская копия GOG игрока (проверена на телефоне).
  **Классика без аддона** (2026-10-25) — вторая кнопка «Играть» на экране запуска: как `Gothic2c.bat` в GOG,
  скрипты `_compiled_GII`, реплики `Cutscene_GII`, без `*_Addon*.vdf`, свои сохранения `files/classic/`
  (`files/game_variant.txt` → `CommandLine::isClassic/saveFile`); ждёт проверки. **Скрипты и реплики — только по
  пути варианта** (`Gothic::loadScript`, `loadDialogOU`): поиск по имени (`Vfs::find`) в «Ночи Ворона» находил
  `_compiled_GII` — до 2026-10-25 «Ночь Ворона» шла на скриптах классики; старые сохранения без метки `g2m/scripts`
  грузятся как раньше (`Gothic::setLegacyScripts`). Отряд и Геральт переходят с героем в другой мир
  (`GameScript::squadLeaveWorld/squadEnterWorld`). Установщик GOG нужно сначала
  распаковать в папку с `System`, `Data`, `_work` (docs/SUPPORTED_BUILDS.md).
- **Пиратские копии не используем** (ни для тестов, ни для релиза): не скачивать, не предлагать, не
  поддерживать взломанные файлы. Игровых файлов у нас нет: проверяет на своём телефоне только пользователь.
  Скрипты игры для справки — восстановленные исходники MDK (github.com/VaanaCZ/gothic-2-addon-scripts,
  ветка `2.7-RU`), только для чтения в scratchpad, в репозиторий не класть.
- Язык общения и документации — **русский**.

## Текущее состояние (2026-10-21)

Работает на телефоне пользователя (Adreno 740): запуск из папки игрока, русский текст, без мерцания.
Пользователь подтвердил бой и автобой; новое управление (docs/CONTROLS.md), отряд, «Собрать», напарники и
новая графика ждут проверки. 2026-10-22: отряд до 6, напарники без смерти и обыска, зелья, обмен, привал;
«Собрать» — 20 м и без остановок у тел; **быстрое сохранение** отдельной кнопкой и **«Путь»** — быстрое
перемещение бегом или мгновенно (docs/CONTROLS.md). Там же **оптимизация движка под телефон** (docs/PROGRESS.md):
ждёт проверки и журнала со строками `[perf]`. **Живой NPC — Геральт из Ривии** (**docs/AI_NPC.md**): сидит в
кабаке Корагона в Хоринисе, думает языковая модель по ключу игрока (Ollama Cloud / любой адрес, совместимый
с OpenAI), голос — Gemini/Speechify/Azure/ElevenLabs/свой/телефон, память по сохранениям; кнопка «Сказать» рядом с ним
(без диалога), в диалоге сам не заговаривает, надевает/снимает снаряжение, обмен вещами в отряде. **Живые
жители:** «Сказать» обращается к любому человеку, на которого смотрит герой, — отвечает его модель с теми же
инструментами, что у Геральта, и своим характером (`NpcLore`, его реплики из игры); ждёт проверки на телефоне.
2026-10-24: **сюжет игры через разговор** — живой житель играет темы своих диалогов из скриптов игры (`story_topic`:
задания, их сдача, награды, пропуска, обучение — тем же скриптом, что родной диалог; реплики пересказывает модель;
docs/AI_NPC.md, «Сюжет игры в разговоре»); **«Путь» бегом лечит героя** при здоровье меньше 70 %: зелья → травы →
еда (`PlayerControl::travelHeal`). Оба ждут проверки на телефоне. Там же: **меню игры под палец** (главное, в игре,
журнал, сохранения, опции — `Ui::Menu`: палец ведёт подсветку, лупа, стрелки, «Выбрать», «Назад»; журнал без вложенных
окон; docs/CONTROLS.md, «Меню игры…») и **бессмертные люди теряют сознание вместо неуязвимости** (`Npc::isKnockoutOnly`:
жизни уходят, убить и добить нельзя; звери и драконы — как в оригинале) — ждут проверки. **Драки людей — до потери
сознания:** `set_attitude hostile` у жителя — временное «враждебно» поверх «сердито», а не постоянная вражда (с ней
движок вёл бой насмерть в обе стороны — `Npc::checkHealth`, `attackReason`); старые сохранения правит
`AiNpc::calmHostility` (docs/PROGRESS.md, последний раздел). **Жители снова говорят друг с другом** — реплику слышит только герой,
другому — `talk_to_npc` (`Persona.TALK_OTHERS`), слушающий отвечает и делом (`Tools.listener`, `Persona.reactions`);
беседа NPC с NPC идёт несколько реплик внутри `talk_to_npc` (`GeraltBrain.converse`), слушающий может заговорить с
третьим; ждёт проверки. 2026-10-25: **регдолл** — убитые и оглушённые люди и звери падают по физике Bullet (удар,
разбег, склоны, вода), оглушённые встают с той стороны, как лежат; тела застывают и пишутся в сохранение
(docs/PROGRESS.md, «Регдолл…»; проверено на стенде `tools/ragdoll-test`, на телефоне — ждёт проверки).

- **Приложение** (`app/`, Java, пакет `gothic2.mobile.og`): `LauncherActivity` — разрешение «Файлы», поиск
  папки игры (`GameFolder`), выбор вручную через SAF, «Играть», раздел «Графика» (`GraphicsSettings` →
  `files/g2m.ini`; на слабом телефоне при первом запуске — «Экономно», `DevicePerformance`), «Сохранить журнал в «Загрузки»» (`gothic2-log.txt`), «Сведения о лицензиях»
  (`LicensesActivity`). `GameActivity` — `NativeActivity` движка (`libGothic2Notr.so`) во весь экран, частота дисплея — под
  ограничение кадров; **в своём процессе `:game`** — экран запуска перезапускает игру (`LauncherActivity.restartGame`).
  Кнопка «Приложение» в главном меню игры (`TouchInput` `C_App` → `AppLink` JNI → `GameActivity.openAppMenu`) — экран
  запуска поверх игры. **Свёрнутая игра не закрывается** (патч Tempest `androidapi.cpp`: потеря окна — пауза, слот окна,
  `VSwapchain::reset` по номеру окна; вернулись посреди игры — меню паузы; без фокуса звук на паузе).
- **Движок** (`third_party/opengothic`, скачивает `tools/fetch-opengothic.sh`; патчи
  `third_party/opengothic.patch` и `opengothic-tempest.patch`):
  - меню игры (`GameMenu`, `MenuRoot`): сенсорный слой лежит поверх меню (`Ui::Menu`) и отдаёт ему касания мимо
    кнопок; ближайший пункт под пальцем и лупа, журнал — состояния `logList`/`logView` (не `Dialog::exec`: вложенное
    окно забирает все касания);
  - сенсорный слой `common/ui/touchinput.*` — схема в **docs/CONTROLS.md**: полоски слева вверху, дуги под
    правым пальцем (главная кнопка по обстановке, «Оружие» с колесом оружия и заклинаний, «Прыжок», «Блок»,
    «Зелье», «Автобой», «Собрать», «Отряд»), справа вверху «Сумка», «Карта», «Путь», «Журнал», «Герой»,
    «Сохранить», «Меню»; касание по NPC — цель, по реплике/ячейке — выбор; значки рисуются в `touchicons.*`
    (поле расстояний, без текстур в APK);
  - быстрое сохранение (`MainWindow::touchQuickSave`: в бою нельзя, итог — «Игра сохранена»; подержать —
    быстрая загрузка с подтверждением); любое сохранение — во временный файл и замена одним шагом;
  - «Путь» — быстрое перемещение: места `common/game/travelpoints.*` (62, метки разработчиков, точки рун и
    распорядков; карты и координаты — как у карт-предметов), карта с путём `TouchInput::drawTravelMap` (игра на
    паузе), бегом — `PlayerControl::tickTravelRun` (сеть путей, бой автобоем, сбор добычи, стража, застревание;
    галочки «Сражаться»/«Собирать лут» — `TravelOptions`), мгновенно — `tickTravelFade` + `GameScript::squadArrive`;
    камера в пути — `common/game/travelcamera.*` (ракурсы и «кино»; откуда и куда смотреть — `Camera::setDirector`,
    палец крутит только камеру — `MainWindow::tickMouse`);
  - отряд (`companions.cpp`: `squadOrder`, `tickSquad`): «ко мне», «ждать», «атаковать цель», «не нападать»
    (`aivar[95]`), напарники вступаются за героя через `B_Attack`; «Собрать» (`PlayerControl::tickGather`);
  - удержание «Удара» = удар только в окно комбо (`Pose::isComboWindow`, `PlayerControl::tickTouchAttack`);
  - автобой по боевому ИИ игры (`Npc::autoFight`, `fightTactic`, `PlayerControl::tickAutoFight`), зелья;
  - напарники (`common/game/companions.cpp`): найм безымянных NPC в диалоге за 10 золотых/уровень (до 6),
    `AIV_PARTYMEMBER` + `ZS_Follow_Player`, опыт за их убийства даёт сама игра (`ZS_Dead`); не погибают
    (`Npc::onNoHealth` → без сознания, `canFinish`), не обирают и не обираются, зелья перед `ZS_HealSelf`
    (`AI_StartState` → `GameScript::companionState`); не обижаются на героя (`companionPerceives`,
    `companionForgive`); выпавшее оружие ничьё (`ITM_DROPPED`); обмен даром (`DlgChoice::freeTrade`); привал —
    случайные занятия и беседы (`tickSquadIdle`, цикл распорядка молчит — `Npc::isCompanionIdle`);
    враги отряда — и те, кого ударил герой (`squadNoteHit`); в пути — строй, след героя, путь по точкам,
    телепорт отставших (`tickSquadFollow`, `Npc::goToPos`); «Ждать» — лагерь на месте (`squadCamp`, метка
    `G2M_CAMP`, `Npc::isCompanionCamp`);
  - графика из `g2m.ini` (`Gothic::Gothic`): FPS, внутреннее разрешение, растяжение до экрана, тени
    (выкл/512–2048), анизотропия (`game/main.cpp`, до устройства), ветер, отражения, лучи солнца, дальность
    прорисовки и отсечение мелочи (`visibility_pass.comp`), счётчик кадров; рендер ждёт видеокарту;
  - **оптимизация под телефон (2026-10-22, docs/PROGRESS.md, последний раздел):** скелеты NPC по дальности
    (`Npc::animLodDue`), ветер только у камеры, дымка у границы прорисовки (`fog.frag`), трава редеет без линии,
    тени без мелочи, предметы на земле видны втрое дальше, небо раз в 4 кадра, точный ограничитель кадров,
    подсказки планировщику и перегрев (`utils/mobileperf.*`), охлаждение 30 FPS; **строка `[perf]` в журнале
    раз в 10 с** — по ней видно, во что упирается телефон (`gpu-wait` — видеокарта, `logic`/`anim` — процессор);
  - жарка мяса (`common/game/cooking.cpp`): у сковороды с 2+ кусками — диалог героя с собой «1 / 5 / 10 / всё»;
  - **регдолл** (`common/physics/ragdoll.*`): кукла по скелету (капсулы, шарниры колен и локтей у людей, конусы),
    своя категория `C_Ragdoll` — живые NPC, камера и лучи тела не видят; `Npc::startRagdoll` из `onNoHealth`, удар —
    `ragHit` из `takeDamage`; позиция NPC за тазом, `MoveAlgo` у лежащего выключен; подъём — `AI_StandUp` →
    `ragdollGetUp`; поза — `Pose::setRagdoll` (ведомые кости от куклы, прочие — в позе момента падения); сохранение —
    запись `…/npc/<id>/ragdoll`; настройка `ragdoll` в `g2m.ini` («Падение побеждённых»);
  - «Взять всё» (`InventoryMenu::takeAll`), путь к игре из `gothic_path.txt` (`InstallDetect`), имя сохранения
    по умолчанию — дата, кнопка «Назад» телефона = Esc (Tempest);
  - **живой NPC** (`common/game/ainpc.*`, JNI — `ailink.*`): Геральт = `Vlk_2000_Thorus` с другим именем и
    внешностью (доспехи рудного барона), снимок обстановки, действия (и `equip`/`unequip`; своё снаряжение скрипты не меняют —
    `keepsOwnGear`), реплики модели через `GameScript::messageByName/messageTime` и `DialogMenu`, кнопка
    «Сказать» (`TouchInput` `C_Say` → `AiNpc::addressee/heroSpeak`; живые жители — команды с `who`, `refSelf`,
    `Npc::saveRoutines`; сюжетные темы — `AiNpc::cmdStory`/`storyJson`, `GameScript::storyExec`, реплики темы — в
    `StoryCapture`, а не в очередь ИИ); приложение — пакет `gothic2.mobile.og.ai` (`GeraltBrain`, `LlmClient`, `Tts`,
    `Memory`, характер — `Persona`, сюжетные темы — `Story`), настройки — `AiSettingsActivity`.
- Сохранения движка — `files/save_slot_N.sav` в каталоге приложения (формат OpenGothic; с сохранениями
  оригинальной игры не совместимы). Удаление приложения их удаляет.
- Id `gothic2.mobile.og` оставлен от пробной сборки намеренно: обновление ставится поверх неё, сохранения
  остаются. Не менять без переноса сохранений.

## Следующие действия

1. Ждём от пользователя: Геральта (docs/TEST_PLAN.md, пункт 10: ключ, голос, разговор, действия, память), новое управление, отряд в драках с людьми, «Собрать», «Путь» (стоят ли метки там, где
   подписаны; камера в пути, галочки), быстрое сохранение, нагрев и FPS после оптимизации — журнал со строками
   `[perf]` (docs/PROGRESS.md, разделы «Ожидается от пользователя»; docs/TEST_PLAN.md). По `[perf]` — следующая
   оптимизация: упор в видеокарту (`gpu-wait`) или в процессор (`logic`, `anim`).
2. Дальше — docs/ROADMAP.md: настройка размера/прозрачности кнопок и левша, надёжные сохранения, классика,
   слабые телефоны, OpenAL Soft (LGPL) перед выпуском.

## Сборка в облачной сессии

В контейнере нет Android SDK — установить (≈3 мин):

```bash
mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp && \
curl -sS -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
unzip -q cmdtools.zip -d /opt/android-sdk/tmp && mv /opt/android-sdk/tmp/cmdline-tools /opt/android-sdk/cmdline-tools/latest && \
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk \
  "platforms;android-35" "build-tools;35.0.0" "platform-tools" "ndk;27.0.12077973" "cmake;3.22.1" >/dev/null
echo "sdk.dir=/opt/android-sdk" > local.properties   # файл в .gitignore
```

Движку нужны `glslangValidator` и **свежие заголовки Vulkan** (в Ubuntu и NDK старые — нужен
`VK_EXT_descriptor_heap`):

```bash
apt-get install -y glslang-tools
git clone -q --depth 1 https://github.com/KhronosGroup/Vulkan-Headers /tmp/vkh && \
  mkdir -p /opt/vulkan-sdk && cp -r /tmp/vkh/include /opt/vulkan-sdk/   # путь: vulkan.sdk в local.properties, VULKAN_SDK или /opt/vulkan-sdk
tools/fetch-opengothic.sh   # закреплённый OpenGothic с подмодулями → third_party/opengothic + наши патчи
```

Проверка перед каждым пушем (сборка движка с нуля ≈ 30 мин, дальше — минуты):

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- APK для пользователя: `app/build/outputs/apk/debug/app-debug.apk` (≈ 17 МБ, только arm64-v8a; лимит
  отправки файла здесь — 30 МБ). Шейдеры для Android собираются без `-g` (`shader/CMakeLists.txt`) — с ним
  SPIR-V втрое больше. Движок и в debug собирается как Release (отладочный — сотни МБ).
  Подпись — общий `app/debug.keystore`: новый APK ставится поверх старого. Если APK вдруг вырос вдвое —
  инкрементальная упаковка оставила «дыры»: удалить `app/build/outputs/apk` и собрать заново.
- **Правки движка** — в `third_party/opengothic` (и `lib/Tempest`), затем в патчи (иначе пропадут при
  следующем `fetch-opengothic.sh`):
  ```bash
  cd third_party/opengothic
  git add -N common/game/companions.cpp common/game/travelpoints.cpp common/game/travelpoints.h common/utils/cp1251.h \
    common/game/travelcamera.cpp common/game/travelcamera.h common/game/ainpc.cpp common/game/ainpc.h \
    common/game/ailink.cpp common/game/ailink.h common/utils/mobileperf.cpp common/utils/mobileperf.h common/utils/perfstats.h \
    common/ui/touchicons.cpp common/ui/touchicons.h common/game/cooking.cpp \
    common/utils/applink.cpp common/utils/applink.h common/physics/ragdoll.cpp common/physics/ragdoll.h
    # и другие новые файлы (все: git ls-files --others --exclude-standard | grep -v ^build/); не -A: build/ не в .gitignore
  git diff --ignore-submodules=all > ../opengothic.patch && git reset -q
  git -C lib/Tempest diff > ../opengothic-tempest.patch
  ```
  Каждую правку помечать комментарием «Gothic II Mobile:». Движок собирается с `-Werror`
  (неявное int→float, лишняя ветка switch — ошибка). Быстрая проверка компиляции без APK — отдельная сборка
  CMake в `third_party/opengothic/build/android` (`cmake --build … --target Gothic2Notr`).
- Maven Central иногда отвечает **429** — повторить или `./gradlew --init-script tools/maven-mirror.gradle …`.
- Эмулятор в контейнере не работает (нет KVM) — на устройстве проверяет только пользователь.
- GitHub Actions в репозитории не получает раннер (ограничение аккаунта): CI в PR красный не из-за кода.

## Правила кода (выучено на ошибках)

- **Шрифты игры — Windows-1251.** Строки наших кнопок и реплик в движке переводить через
  `utils/cp1251.h` (`toCp1251`), иначе вместо кириллицы мусор. В шрифтах нет «ё», длинного тире и «ёлочек» —
  `toCp1251` меняет их на «е», «-» и '"'.
- **`Painter::Add` в Tempest — смешивание ONE, ONE**: прозрачность кисти и текстуры не учитывается. Текстуры
  для сложения — с цветом, умноженным на прозрачность (как `TouchIcons::glow`), силу задаёт яркость цвета;
  иначе на экране квадрат. Для обычного наложения — `Painter::Alpha`.
- Настройки видео в меню игры OpenGothic почти не читает (только `zVidBrightness/Contrast/Gamma`,
  `zEnvMappingEnabled`, `zCloudShadowScale` = SSAO, `zFogRadial` = лучи солнца, `zWindEnabled`, `zMaxFps`,
  `[INTERNAL] vidResIndex`). Новую графику — через `g2m.ini` и экран запуска, а не через меню игры.
- Новые возможности по возможности делать **механикой самой игры** (скрипты, перцепции, флаги aivar), сверив
  имена с восстановленными скриптами: так работают сохранения, опыт и реакции NPC.
- Касание мимо кнопок не должно становиться ударом или выбором: `TouchInput` забирает касания в мире и на
  экранах со списками; `MainWindow::mouse*` на телефоне ничего не делают. Выбор реплики/ячейки касанием —
  в два шага (выделить, подтвердить).
- Раскладку кнопок проверять макетом в scratchpad (те же формулы `TouchInput::buttonOf`, значки из
  `touchicons.cpp`, собранного на компьютере с заглушками Tempest): зазоры, перекрытия, полоски игры.
- Новый `.cpp` в движке: CMake собирает исходники через `GLOB` — в готовом каталоге сборки
  (`app/.cxx/Release/*/arm64-v8a`) перезапустить `cmake .`, иначе файл не попадёт в сборку.
- **Регдолл:** физику куклы проверять на компьютере стендом `tools/ragdoll-test` (настоящие `ragdoll.cpp` и Bullet,
  скелеты человека и волка, земля; `build.sh`, затем `./ragtest` — «ALL OK»), а не только сборкой. Тела куклы —
  категория `C_Ragdoll`: новая проверка столкновений или луч в `DynamicWorld` должен их пропускать (как
  `needsCollision` с `C_Landscape/C_Object`), иначе живые NPC споткнутся о трупы.
- Новая работа движка на кадр — с оглядкой на телефон: не перебирать каждый кадр все объекты мира (NPC, ветер,
  свет) без отсечения по дальности; счётчики — в `utils/perfstats.h` и строку `[perf]`. ИИ и движение дальних
  NPC реже не тикать: большой шаг движения проходит сквозь стены.
- Код экрана запуска — Java без AndroidX; логику без Android выносить в отдельные классы (`GameFolder`,
  `GraphicsSettings`) и покрывать тестами (`app/src/test`): статические поля `Activity` с `Color` и т. п.
  ломают модульные тесты.
- Любой внешний ввод (папка игры, путь SAF) — недоверенный: не-файловые тома SAF отклоняются (`docIdToPath`).
- Никаких игровых файлов в репозитории (`.gitignore` исключает `*.vdf`, `*.mod`, `game-data/`).
- Комментарии и строки интерфейса — по-русски, в стиле окружающего кода.
- **Живой NPC:** действия тела (идти, следовать, бой, занятие) нельзя класть в очередь ИИ во время диалога —
  конец `ZS_Talk` и `Npc::startState` очищают её; сначала конец разговора, затем `resumeAiRoutine`, затем
  движение (`AiNpc::beginBody`). Строки для Java — только UTF-8 байтами (`fromCp1251`), не `NewStringUTF`.
  Java-методы движка находятся только после `System.loadLibrary("Gothic2Notr")` в `GameActivity`.
  JSON в Java — свой `ai/Json` (org.json в модульных тестах — заглушка), в движке — rapidjson в
  `#pragma GCC diagnostic ignored "-Wdeprecated-declarations"`.

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
