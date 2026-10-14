# Сторонние компоненты

Для каждого компонента, попадающего в APK или загружаемый пакет, фиксируются версия, источник,
лицензия, список изменений, необходимые уведомления и исходники. Закреплённые версии и SHA-256 —
в `app/src/main/assets/runtime_components.ini`.

| Компонент | Источник | Лицензия | Статус |
|---|---|---|---|
| Winlator 11.2: X-сервер, интеграция, rootfs, компоненты (`.tzst`) | github.com/brunodev85/winlator-app, коммит `3981d86e` | LGPL-2.1 | **используется** (модуль `:winlator`); изменения — `third_party/winlator.patch` в этом репозитории |
| Box64 0.4.4 (из Winlator) | github.com/ptitSeb/box64 | MIT | используется |
| Wine (WoW64, из rootfs Winlator) | gitlab.winehq.org/wine/wine | LGPL-2.1-or-later | используется |
| DXVK 1.10.3/2.4.1, D7VK 1.11, VKD3D (из Winlator) | github.com/doitsujin/dxvk и др. | Zlib / LGPL | используется |
| Mesa (Turnip/Zink/VirGL), glibc и пакеты rootfs (из Winlator) | mesa3d.org, termux-pacman | MIT / LGPL и др. | используется |
| Обёртка D3D7→D3D9 (LegacyAltRenderer) | github.com/SaiyansKing/Gothic-LegacyAltRenderer | проверить в репозитории | архивирован; совместимость с Classic не подтверждена |
| AndroidX (core, appcompat, activity, preference) | developer.android.com | Apache-2.0 | используется |
| Kotlin stdlib | kotlinlang.org | Apache-2.0 | используется |

Компоненты среды берутся из Winlator 11.2 как есть (его `assets`), кроме замены пути rootfs при
распаковке. Архивы `*.tzst` в APK не входят: приложение скачивает их с GitHub закреплённого коммита
и проверяет по SHA-256 из `remote_assets.txt` (создаётся при сборке). `runtime_components.ini` и `ComponentInstaller` этой схемой не используются.
