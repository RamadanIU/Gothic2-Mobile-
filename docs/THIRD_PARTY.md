# Сторонние компоненты

Для каждого компонента, попадающего в APK или загружаемый пакет, фиксируются версия, источник,
лицензия, список изменений, необходимые уведомления и исходники. Закреплённые версии и SHA-256 —
в `app/src/main/assets/runtime_components.ini`.

| Компонент | Источник | Лицензия | Статус |
|---|---|---|---|
| Основа среды (rootfs, X-сервер, интеграция) | github.com/brunodev85/winlator | LGPL-2.1 | кандидат; производная работа → исходники изменений публикуются |
| Box64 | github.com/ptitSeb/box64 | MIT | кандидат |
| Wine (WoW64) | gitlab.winehq.org/wine/wine | LGPL-2.1-or-later | кандидат |
| DXVK | github.com/doitsujin/dxvk | Zlib | кандидат для цепочки D3D9 |
| Обёртка D3D7→D3D9 (LegacyAltRenderer) | github.com/SaiyansKing/Gothic-LegacyAltRenderer | проверить в репозитории | архивирован; совместимость с Classic не подтверждена |
| AndroidX (core, appcompat, activity, preference) | developer.android.com | Apache-2.0 | используется |
| Kotlin stdlib | kotlinlang.org | Apache-2.0 | используется |

До выбора окончательных версий (этап 1) ни один компонент среды в APK не включается.
