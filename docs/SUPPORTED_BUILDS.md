# Поддерживаемые сборки Gothic II

## Цель

Отдельная Gothic II Classic больше не продаётся. Лицензионные копии — **Gothic II: Gold**
(Steam, GOG, диски «Золотое издание»), где «Ночь Ворона» встроена. Поэтому:

| Сборка | Варианты запуска | Статус |
|---|---|---|
| `g2-gold-gog` — GOG «Gothic II: Gold Classic» (THQ Nordic) | «Ночь Ворона» (`Gothic2.exe -game:GothicGame.ini`); классика (`Gothic2c.exe`, аргументы уточняются) | **закреплена** по отчёту 2026-10-14 |
| `g2-gold-disc-ru` — Золотое издание с диска | «Ночь Ворона» | не закреплена |
| `g2-classic-ru` — Classic с диска 2002–2003 гг. | один | не закреплена |

### Что внутри GOG-издания (по отчёту)

Около 4,1 ГБ, 1856 файлов. Кроме самой игры, GOG ставит:
- **GD3D11** — графический мод D3D11 (`System/GD3D11/`, свой `System/ddraw.dll`, `d3dcompiler_47.dll`,
  `GFSDK_SSAO`). По плану графические моды по умолчанию не включаются: цепочка WineD3D его не
  загружает (`ddraw=b`), цепочка DXVK заменяет `ddraw.dll` обёрткой, а оригинал переименовывает
  (`RendererSetup`).
- **Union** (`System/Union.patch`, `Data/Union.vdf`, `Data/$Templates$/*.dll`) и **SystemPack**
  (`System/SystemPack.ini`, `vdfs32g.dll`) — исправления движка и плагины; под Wine проверить.
- **DSOAL** (`System/Autorun/dsound.dll`, `dsoal-aldrv.dll`) — звук через OpenAL; проверить под Wine.
- **Отдельный `System/Gothic2c.exe`** + `Gothic2c.ini` + `Data/modvdf/Gothic2c.mod` + `Gothic2c.bat` —
  классическая Gothic II. Ещё есть мод `g2_classic` (`System/g2_classic.ini`, `g2_classic(_ru).mod`).
- Моды сообщества для GothicStarter: ScriptPatch v1/v32 (в т.ч. `_ru`, `_widescreen`), Gothic 2 Mod Fix.
- `System/dxvk.conf` (заменяется настройками приложения при запуске).

Язык поставки не определён: в `Data` есть английские `Speech_*_engl`, `Textures_Addon_Menu_English`,
а в `modvdf` — русские `g2_classic_ru`, `scriptpatch_v32_ru`.

Сборки различаются составом файлов: у Gold есть `Data/Worlds_Addon.vdf`, у отдельной Classic его
быть не должно (`forbids=`). Окончательно сборка опознаётся по SHA-256 `System/Gothic2.exe`.

Взломанные EXE (NoCD и т.п.) не поддерживаются: обход защиты не входит в проект, а у изменённого
EXE своя контрольная сумма.

## Как получить папку игры из установщика GOG

GOG выдаёт офлайн-установщик (`setup_gothic_2_gold_….exe` + несколько `.bin`), а приложению нужна
**установленная** игра — папка с `System` и `Data`. Способы:

1. **ПК с Windows:** установить игру и скопировать папку установки на телефон.
2. **Termux + innoextract:** положить `.exe` и все `.bin` в одну папку и выполнить
   `innoextract --gog setup_*.exe`; игра окажется в подпапке `app`. Старые установщики GOG хранят
   `.bin` как RAR — тогда нужен ещё `unrar`. Не проверено на телефоне.
3. **Winlator:** запустить `setup.exe` в Winlator и скопировать папку игры с его диска C:.

Встроенная распаковка установщика GOG в приложении — возможная доработка после этапа 1
(docs/ROADMAP.md, этап 2).

## Как закрепить сборку — без пересылки игры

1. Установить debug-APK на телефон и скопировать на телефон папку установленной игры (USB/карта памяти).
2. В приложении выбрать эту папку. На экране проверки нажать **«Сохранить отчёт о сборке»**
   (после импорта то же самое есть в Настройках → «Отчёт о сборке игры»).
3. Прислать полученный `gothic2-build-report.txt` (несколько КБ). В нём только относительные пути,
   размеры и SHA-256; сохранений и путей устройства в нём нет.
4. По отчёту заполняются `exeSha256`, `requires`, `requiresSha256`, а для Gold — способ запуска
   классического варианта (какой `.ini` в `System` и архив в `Data/modvdf` его задают).
5. Пройти критерий этапа 1 (docs/ROADMAP.md) на телефоне.

Debug-сборка принимает незакреплённые копии (режим разработчика), release — только закреплённые.

## Формат манифеста

`app/src/main/assets/supported_builds.ini`:

```ini
[build:g2-gold-ru]
title=Gothic II: Золотое издание (рус.)
exe=System/Gothic2.exe
exeSha256=PENDING                 ; ← из отчёта о сборке
language=ru
requires=Data/Worlds.vdf,...,Data/Worlds_Addon.vdf
forbids=                          ; файлы, которых в сборке быть не должно
requiresSha256=                   ; путь:sha256 ключевых архивов — из отчёта
saves=Saves
ini=System/GOTHIC.INI
variants=notr,classic

[variant:g2-gold-ru/notr]
title=Gothic II: Ночь Ворона
args=                             ; аргументы Gothic2.exe

[variant:g2-gold-ru/classic]
title=Gothic II (классическая, без дополнения)
args=PENDING                      ; PENDING — вариант не предлагается, пока не подтверждён
saves=                            ; свой каталог сохранений, если вариант его использует
```

Сборка без `variants=` имеет один вариант запуска без аргументов. Имена файлов сравниваются без учёта
регистра. Резервные копии сохранений хранятся раздельно для каждого варианта.
