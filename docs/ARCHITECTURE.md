# Архитектура

## Общая схема

```
LauncherActivity (Java)            GameActivity (NativeActivity, Java)
 ├─ разрешение «Файлы»              └─ libGothic2Notr.so — OpenGothic (C++20, Vulkan)
 ├─ папка игры (GameFolder)              ├─ Tempest: окно Android, касания, звук (OpenAL Soft), Vulkan
 ├─ графика (GraphicsSettings)           ├─ ZenKit: VDF, Daedalus-скрипты, миры, модели игры
 ├─ журнал в «Загрузки»                  └─ наш патч: TouchInput, бой, автобой, напарники, графика
 └─ лицензии (LicensesActivity)
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
| «Собрать»: обход бесхозных вещей и мёртвых в 8 м | `PlayerControl::tickGather/findGatherTarget` |
| Отряд: приказы, «не нападать» (`aivar[95]`), защита героя через `B_Attack` | `companions.cpp` (`squadOrder`, `tickSquad`), `GameScript::invokeAttack` |
| Касание мимо слоя — не удар; Esc — системная «Назад» | `MainWindow::mouse*`, `Tempest androidapi.cpp` |
| Удержание «Удара»: удар только в открытое окно комбо | `Pose::isComboWindow`, `PlayerControl::tickTouchAttack` |
| Автобой: герой по боевому ИИ NPC (таблицы `Fight.dat`), выбор цели, зелья | `Npc::autoFight`, `Npc::fightTactic`, `PlayerControl::tickAutoFight` |
| Напарники: реплики в диалоге, найм за золото, `AIV_PARTYMEMBER` + `ZS_Follow_Player` | `common/game/companions.cpp`, `GameScript::dialogChoices/exec` |
| Графика из `g2m.ini`: FPS, разрешение, тени, отражения, лучи солнца | `Gothic::Gothic`, `Renderer::setupSettings` |
| Дальность прорисовки и отсечение мелочи по угловому размеру | `shader/materials/visibility_pass.comp`, `DrawCommands::visibilityPass` |
| Рендер ждёт видеокарту, а не крутит цикл | `MainWindow::render` |
| «Взять всё» | `InventoryMenu::takeAll` |
| Папка игры из `gothic_path.txt`; имя сохранения по умолчанию — дата | `InstallDetect`, `GameMenu::SavNameDialog` |
| Путь к заголовкам Vulkan параметром CMake | `Tempest Engine/CMakeLists.txt` (`TEMPEST_VULKAN_SDK`) |

## Ввод

Tempest превращает касания в события мыши с номером пальца (`mouseID`). `TouchInput` — верхний слой над
игрой (ниже только меню игры, у которого своё касание): в мире он забирает все касания (кнопки, джойстик,
обзор, касание по NPC), на экранах со списками — тоже: касание по реплике или ячейке передаётся в
`DialogMenu`/`InventoryMenu` только как осознанный выбор (выделить, затем подтвердить). Действия героя идут
прямо в `PlayerControl` (не через клавиши), навигация и Esc — синтетическими клавишами в `MainWindow`.
Раскладка и жесты — docs/CONTROLS.md.
Управление на телефоне — всегда схема Gothic II (`g2Ctrl`).

## Сборка

`app/build.gradle.kts`: id `gothic2.mobile.og` (как у пробной сборки — обновление ставится поверх с
сохранениями), minSdk/targetSdk 28, только arm64-v8a, `versionCode = 100 + число коммитов`, подпись — общий
`app/debug.keystore`. Задача `generateLicenses` собирает тексты лицензий движка в ассеты экрана лицензий.
