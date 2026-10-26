pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Gothic2Mobile"

// app — Android-приложение: экран запуска (папка игры, графика), экран игры и движок OpenGothic
//       (C++, собирается через CMake из third_party/opengothic — его скачивает tools/fetch-opengothic.sh
//       и накладывает наши патчи third_party/opengothic*.patch).
include(":app")
