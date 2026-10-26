# Сторонние компоненты

Для каждого компонента, попадающего в APK или загружаемый пакет, фиксируются версия, источник,
лицензия, список изменений, необходимые уведомления и исходники. Закреплённые версии и SHA-256 —
в `app/src/main/assets/runtime_components.ini`.

| Компонент | Источник | Лицензия | Статус |
|---|---|---|---|
| Winlator 11.2: X-сервер, интеграция, rootfs, компоненты (`.tzst`) | github.com/brunodev85/winlator-app, коммит `3981d86e` | LGPL-2.1 | **используется** (модуль `:winlator`); изменения — `third_party/winlator.patch` в этом репозитории |
| Box64 0.4.4 (из Winlator) | github.com/ptitSeb/box64 | MIT | используется |
| Wine (WoW64, из rootfs Winlator) | gitlab.winehq.org/wine/wine | LGPL-2.1-or-later | используется |
| DXVK 1.10.3/2.4.1, D7VK 1.11, VKD3D (из Winlator) | github.com/doitsujin/dxvk, github.com/WinterSnowfall/d7vk и др. | Zlib / LGPL | используется; **D7VK изменён** (см. ниже) |
| Mesa (Turnip/Zink/VirGL), glibc и пакеты rootfs (из Winlator) | mesa3d.org, termux-pacman | MIT / LGPL и др. | используется |
| Обёртка D3D7→D3D9 (LegacyAltRenderer) | github.com/SaiyansKing/Gothic-LegacyAltRenderer | проверить в репозитории | архивирован; совместимость с Classic не подтверждена |
| Шрифты Alegreya и Alegreya SC (`res/font`) | fonts.google.com, github.com/huertatipografica/Alegreya | OFL-1.1 | используется: весь текст интерфейса; уведомление и текст лицензии — `assets/licenses/OFL-1.1.txt` |
| Контур буквы «G» из шрифта UnifrakturMaguntia (значок, эмблема, значок уведомления) | fonts.google.com | OFL-1.1 | только контур одной буквы, перерисованный в векторный ресурс (сам шрифт в APK не входит); указан в «Сведениях о лицензиях» |
| AndroidX (core, appcompat, activity, preference) | developer.android.com | Apache-2.0 | используется |
| Kotlin stdlib | kotlinlang.org | Apache-2.0 | используется |

Компоненты среды берутся из Winlator 11.2 как есть (его `assets`), кроме замены пути rootfs при
распаковке. Архивы `*.tzst` в APK не входят: приложение скачивает их с GitHub закреплённого коммита
и проверяет по SHA-256 из `remote_assets.txt` (создаётся при сборке).

**Изменённый D7VK 1.11.** В режимах D7VK вместо `ddraw.dll` D7VK 1.11 из Winlator (`syswow64`, SHA-256
`0e232ec5…`) работает наша сборка того же D7VK 1.11 (тег `v1.11`, коммит `6905c886`) с патчем
`third_party/d7vk.patch`: исправлена ошибка `DDraw7Interface::GetCaps` (падение Gothic при запуске; в D7VK
2.x исправлено автором) и выгрузка mip-уровней текстур, записанных после первой выгрузки (чёрные далёкие
объекты). Сборка — `tools/build-d7vk.sh` (воспроизводимая), файл — `app/src/main/assets/d7vk/ddraw.dll`
(SHA-256 `4191ffb5…`), подмена — `FileReplacement.D7VK_1_11_GOTHIC`; если она не удалась, исправляется
только байт GetCaps (`BinaryFix.D7VK_1_11_HEL_CAPS`, SHA-256 `034b1a2f…`). Это изменённые версии, а не
оригинальный D7VK (требование лицензии Zlib). `runtime_components.ini` и `ComponentInstaller` этой схемой не используются.

**Уведомления в приложении.** Игрок нигде не видит названий среды исполнения, кроме экрана
«Настройки → Сведения о лицензиях» (`LicensesActivity`): там перечислены авторы и лицензии
шрифтов и компонентов, а полные тексты LGPL-2.1, OFL-1.1, MIT, zlib и Apache-2.0 лежат в
`app/src/main/assets/licenses/`. Это требование LGPL (уведомление и текст лицензии с каждой копией)
и OFL/MIT/zlib/Apache.

**Ресурсы Winlator, переопределённые приложением** (чтобы на экране не было его названия и значка):
`layout/preloader_dialog` (пустое окно вместо «Starting up…»), `drawable/icon_notification`,
строки `app_name`, `starting_up`, `updating_system_files`, `installing_system_files`, `fgs_*`
(`values*/winlator_overrides.xml` — во всех языках Winlator: values, values-ru, values-pt). Свои
значок и название приложение задаёт под уникальными именами (`mipmap/gothic_icon`,
`string/game_title`): у Winlator есть `mipmap-anydpi-v26/ic_launcher` и `app_name` в values-ru,
которые иначе перекрыли бы наши на Android 8+ и на русском языке.
