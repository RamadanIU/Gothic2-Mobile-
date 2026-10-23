# Архитектура

## Общая схема

```
LauncherActivity (Java)            GameActivity (NativeActivity, Java)
 ├─ разрешение «Файлы»              └─ libGothic2Notr.so — OpenGothic (C++20, Vulkan)
 ├─ папка игры (GameFolder)              ├─ Tempest: окно Android, касания, звук (OpenAL Soft), Vulkan
 ├─ графика (GraphicsSettings)           ├─ ZenKit: VDF, Daedalus-скрипты, миры, модели игры
 ├─ журнал в «Загрузки»                  ├─ наш патч: TouchInput, бой, автобой, напарники, графика
 ├─ живой NPC (AiSettingsActivity)       └─ живой NPC: AiNpc ←JNI (AiLink)→ ai/AiBridge → GeraltBrain
 └─ лицензии (LicensesActivity)              (модель, голос, память — docs/AI_NPC.md)
        │  files/gothic_path.txt (путь к папке игры)
        └─ files/g2m.ini        (графика)  ──► читает движок при старте
```

Игровые файлы читаются прямо из папки игрока (targetSdk 28 + разрешение «Файлы» = старое хранилище).
Свои файлы движок держит в каталоге приложения (`files/`): `Gothic.ini` (его настройки), сохранения
`save_slot_N.sav` (формат OpenGothic, с оригинальной игрой не совместим), `log.txt`, `crash.log`.
Один запуск игры на процесс: после выхода из игры процесс завершается (так устроен Tempest).

## Движок и патчи

Исходники OpenGothic (коммит в `gradle.properties`, `opengothic.commit`) скачивает `tools/fetch-opengothic.sh`
в `third_party/opengothic` (в .gitignore) и накладывает `third_party/opengothic.patch` и
`third_party/opengothic-tempest.patch`. Модуль `:app` собирает движок через `externalNativeBuild`
(CMake 3.22.1, NDK 27, цель `Gothic2Notr`, всегда Release). Каждая наша правка в коде движка помечена
«Gothic II Mobile:».

| Что | Где (в патче) |
|---|---|
| Сенсорный слой (docs/CONTROLS.md): раскладка по дугам, главная кнопка по обстановке, колёса оружия и отряда, касание по NPC/реплике/ячейке, взлом, «Пропустить» | `common/ui/touchinput.*`, `MainWindow::touch*`, `DialogMenu::touchChoice*`, `InventoryMenu::touch*` |
| Рисованные значки и подложки кнопок (сглаживание по полю расстояний, без текстур в APK) | `common/ui/touchicons.*` |
| Полоски здоровья/маны/дыхания слева вверху (не под пальцами) | `MainWindow::paintEvent`, `touchHudBottom` |
| Цель касанием: фокус боя держится на выбранном NPC | `PlayerControl::setTouchTarget/applyTouchTarget` |
| «Собрать»: обход бесхозных вещей, оружия убитых и мёртвых в 20 м; за деревьями и углами — через сеть путей | `PlayerControl::tickGather/findGatherTarget/gatherReach/gatherGo` |
| Отряд: приказы, «не нападать» (`aivar[95]`), защита героя через `B_Attack` | `companions.cpp` (`squadOrder`, `tickSquad`), `GameScript::invokeAttack` |
| Касание мимо слоя — не удар; Esc — системная «Назад» | `MainWindow::mouse*`, `Tempest androidapi.cpp` |
| Удержание «Удара»: удар только в открытое окно комбо | `Pose::isComboWindow`, `PlayerControl::tickTouchAttack` |
| Автобой: герой по боевому ИИ NPC (таблицы `Fight.dat`), выбор цели, зелья | `Npc::autoFight`, `Npc::fightTactic`, `PlayerControl::tickAutoFight` |
| Напарники: реплики в диалоге, найм за золото (до 6), `AIV_PARTYMEMBER` + `ZS_Follow_Player` | `common/game/companions.cpp`, `GameScript::dialogChoices/exec` |
| Напарник не погибает (без сознания), его не добить и не обобрать, сам не обирает; зелья перед `ZS_HealSelf` | `Npc::onNoHealth`, `Npc::canFinish`, `AI_StartState` → `GameScript::companionState` |
| Обмен «Покажи, что у тебя есть»: торговля даром, без денег | `DlgChoice::freeTrade`, `InventoryMenu::trade(…, free)` |
| Привал: случайные занятия ждущих и отряда рядом со стоящим героем, беседы, скамьи | `GameScript::tickSquadIdle/idleBegin/idlePulse/idleTalk`, `Npc::isCompanionIdle` |
| Лагерь «Ждать здесь»: на месте, точка-метка `G2M_CAMP`, цикл распорядка молчит; сон не уносит напарников | `GameScript::squadCamp`, `Npc::isCompanionCamp`, `Npc::resetPositionToTA` |
| Враги отряда: бьют своих, угрожают, ударены героем (отметки `squadNoteHit` из `Npc::takeDamage`) | `GameScript::squadThreat/squadEnemy` |
| В пути: строй позади героя, след героя, путь по точкам, телепорт отставших; точка на земле — `GT_Point` | `GameScript::tickSquadFollow`, `Npc::goToPos`, `Npc::GoTo::isClose` |
| Графика из `g2m.ini`: FPS, разрешение, растяжение до экрана, тени, анизотропия, ветер, отражения, лучи солнца, счётчик кадров | `Gothic::Gothic`, `Renderer::setupSettings`, `game/main.cpp` (анизотропия — до создания устройства) |
| Дальность прорисовки и отсечение мелочи по угловому размеру; свой порог у каждого объекта (трава редеет без линии); в тенях мелочь — вдвое ближе; лежащие предметы — втрое дальше | `shader/materials/visibility_pass.comp`, `DrawCommands::visibilityPass`, `VisualObjects::setCullScale`, `WorldView::addItmView` |
| Дымка у границы прорисовки: дальнее переходит в цвет неба, а не обрывается | `shader/sky/fog.frag`, `Renderer::drawFog` |
| Скелеты NPC по дальности и видимости: рядом — каждый кадр, дальше — через 2/4/8/16 кадров | `Npc::animLodDue/updateAnimationLod`, `WorldObjects::updateAnimation` |
| Ветер — только у камеры (до 60/150 м, затухание к границе), дальше через кадр | `VisualObjects::preFrameUpdateWind` |
| Небо по направлениям (viewLut) — раз в 4 кадра; туман 128×72×48; свет дальше прорисовки не рисуется | `Renderer::prepareSky`, `Renderer::resetSkyFog`, `shader/lighting/light.vert` |
| Анизотропия не выше настройки; простое растяжение до экрана (без Ланцоша) | `Sampler::setAnisotropyLimit` (Tempest), `Renderer::drawTonemapping` |
| Шейдеры в Release без отладочной информации (SPIR-V втрое меньше, APK 27 → 17 МБ) | `shader/CMakeLists.txt` |
| Рендер ждёт видеокарту, а не крутит цикл; точный ограничитель кадров; строка `[perf]` раз в 10 с; охлаждение — 30 FPS при сильном нагреве | `MainWindow::render/perfReport/effectiveFps` |
| Подсказки планировщику (ADPF) и состояние перегрева — из `libandroid.so` по имени | `common/utils/mobileperf.*`, счётчики `common/utils/perfstats.h` |
| «Взять всё» | `InventoryMenu::takeAll` |
| Быстрое сохранение: кнопка, запреты (бой, прыжок, разговор), итог записи, быстрая загрузка с подтверждением | `MainWindow::touchQuickSave/touchQuickSaveInfo/touchQuickLoad`, `MainWindow::tick` (`savePending`), `TouchInput::openConfirm` |
| Сохранение во временный файл и замена одним шагом; очередь ИИ героя (путь, сбор) не сохраняется | `MainWindow::saveGame`, `PlayerControl::onSaveGame` |
| «Путь»: места (метки разработчиков, точки рун и распорядков), карты и их координаты как у карт-предметов | `common/game/travelpoints.*` |
| «Путь»: карта с местами, путь бегом точками, список, пауза игры; затемнение; строка «В пути»; «Назад» телефона | `TouchInput::openTravelMap/drawTravelMap/mapTap`, `MainWindow::keyDownEvent/keyUpEvent` |
| «Путь» бегом: ход по сети путей, бой автобоем, сбор добычи, разговоры и стража, лечение, застревание | `PlayerControl::tickTravelRun/travelGo/travelEnemy/travelUnstuck`, `WayPath::points` |
| «Путь» мгновенно: перенос героя, отряд рядом в строю | `PlayerControl::tickTravelFade`, `GameScript::squadArrive` |
| «Путь» бегом: галочки «Сражаться», «Собирать лут» | `PlayerControl::TravelOptions`, `TouchInput::mapFight/mapLoot` |
| Камера в пути: ракурсы (свободная, издалека, с высоты, сбоку, первое лицо, кино с камерой у дороги), палец крутит только камеру, кнопка и колесо ракурсов | `common/game/travelcamera.*`, `Camera::setDirector/tickDirector`, `MainWindow::tickCamera/tickMouse`, `TouchInput` (`C_Camera`, `Wheel::Camera`) |
| Папка игры из `gothic_path.txt`; имя сохранения по умолчанию — дата | `InstallDetect`, `GameMenu::SavNameDialog` |
| Путь к заголовкам Vulkan параметром CMake | `Tempest Engine/CMakeLists.txt` (`TEMPEST_VULKAN_SDK`) |
| Живой NPC Геральт (docs/AI_NPC.md): вставка в кабак Корагона (и переход туда из пещеры в старых сохранениях), снимок обстановки, действия (и надеть/снять), реплики модели в диалоге и вне его, кнопка «Сказать», итог торговли, метка памяти перед сохранением | `common/game/ainpc.*`, `common/game/ailink.*` (JNI), `GameScript::dialogChoices/exec/messageByName/messageTime/aiOutput`, `DialogMenu::aiOutput/onStart/close/haveToWaitOutput/skipPhrase`, `Npc::onNoHealth/canFinish/takeDamage/resetPositionToTA` и `AI_Equip*` (своё снаряжение), `TouchInput` (`C_Say`), `MainWindow::saveGame/paintEvent` |
| Места «Кабак Корагона» и «Пещера у водопада» в «Пути» | `common/game/travelpoints.cpp` |

## Живой NPC: приложение

`gothic2.mobile.og.ai` (Java, без зависимостей): `AiBridge` — JNI (`System.loadLibrary` в `GameActivity`,
иначе Java-методы движка не находятся), голос и микрофон — сразу, остальные события — в очередь
`GeraltBrain`; `LlmClient` — `/chat/completions` с инструментами, запасной ключ при 429; `Tts` — Gemini,
Azure, ElevenLabs, свой адрес, голос телефона (`AndroidTts`); `VoicePlayer` — AudioTrack; `AiOverlay` —
PopupWindow над экраном движка (микрофон, ввод текста, «думает…», ошибки); `Memory` — файлы
`files/ai/memory/<прохождение>_<метка>.json`; `Observation`, `Persona`, `Speech`, `Tools`, `Json`.
Настройки — `AiSettingsActivity` (SharedPreferences `ai`). Тесты чистой логики — `AiLogicTest`.

## Ввод

Tempest превращает касания в события мыши с номером пальца (`mouseID`). `TouchInput` — верхний слой над
игрой (ниже только меню игры, у которого своё касание): в мире он забирает все касания (кнопки, джойстик,
обзор, касание по NPC), на экранах со списками — тоже: касание по реплике или ячейке передаётся в
`DialogMenu`/`InventoryMenu` только как осознанный выбор (выделить, затем подтвердить). Действия героя идут
прямо в `PlayerControl` (не через клавиши), навигация и Esc — синтетическими клавишами в `MainWindow`.
Раскладка и жесты — docs/CONTROLS.md.
Управление на телефоне — всегда схема Gothic II (`g2Ctrl`).

## Сборка

Экран игры (`GameActivity`) подстраивает частоту дисплея под ограничение кадров: режим дисплея с наименьшей
кратной частотой не ниже 60 Гц (`preferredDisplayModeId`) и `Surface.setFrameRate` (Android 11+). Манифест —
`appCategory="game"`. Экран запуска при первом запуске выбирает «Экономно» на слабом телефоне
(`DevicePerformance`: самое быстрое ядро по `/proc/cpuinfo` и `cpufreq`).

`app/build.gradle.kts`: id `gothic2.mobile.og` (как у пробной сборки — обновление ставится поверх с
сохранениями), minSdk/targetSdk 28, только arm64-v8a, `versionCode = 100 + число коммитов`, подпись — общий
`app/debug.keystore`. Задача `generateLicenses` собирает тексты лицензий движка в ассеты экрана лицензий.
