# Сторонние компоненты

В APK входит движок OpenGothic с его зависимостями (статически в `libGothic2Notr.so`). Тексты лицензий
с копирайтами собирает задача `generateLicenses` (app/build.gradle.kts) из исходников движка — их видно на
экране «Сведения о лицензиях». Игровые файлы в APK не входят.

| Компонент | Источник | Лицензия | Изменения |
|---|---|---|---|
| OpenGothic | github.com/Try/OpenGothic, коммит `01540fe2` | MIT | да — `third_party/opengothic.patch` |
| Tempest (окно, ввод, Vulkan, звук) | github.com/Try/Tempest (подмодуль) | MIT | да — `third_party/opengothic-tempest.patch` |
| ZenKit, dmusic | github.com/GothicKit | MIT | нет |
| Bullet Physics | github.com/bulletphysics/bullet3 | Zlib | нет |
| TinySoundFont, RapidJSON, miniz, libsquish | см. репозитории | MIT | нет |
| zlib, libpng | zlib.net, libpng.org | Zlib, PNG Reference Library License v2 | нет |
| SPIRV-Cross | github.com/KhronosGroup/SPIRV-Cross | Apache-2.0 | нет |
| minivorbis (Ogg/Vorbis) | github.com/edubart/minivorbis | BSD-3-Clause | нет |
| OpenAL Soft (в Tempest) | github.com/kcat/openal-soft | LGPL-2.0-or-later | нет |
| Контур буквы «G» значка из шрифта UnifrakturMaguntia | fonts.google.com | OFL-1.1 | перерисован в векторный ресурс, шрифт в APK не входит |

**OpenAL Soft и LGPL.** Библиотека собрана статически. До публичного выпуска нужно либо собирать её
отдельной `.so`, либо прилагать объектные файлы для пересборки (требование LGPL к статической компоновке).
Для закрытой беты и проверки игроком это не мешает, но перед выпуском — обязательно.

Компоненты прежней версии на Winlator (Wine, Box64, DXVK/D7VK, Mesa, шрифты Alegreya) больше не входят
в приложение — docs/LEGACY_WINLATOR.md.
