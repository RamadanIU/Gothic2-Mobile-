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

// core — чистая Kotlin/JVM-логика без Android (ввод, сохранения, проверка сборки, профили).
// app  — Android-приложение: экран игры, оверлей, импорт, настройки, связь со средой исполнения.
// winlator — среда исполнения (Wine + Box64 + X-сервер) из исходников Winlator; их скачивает
//            tools/fetch-winlator.sh в third_party/winlator-app.
include(":core", ":app", ":winlator")
