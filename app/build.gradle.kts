import java.io.File
import java.util.Properties

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
}

// Движок OpenGothic (C++): исходники с нашими патчами кладёт tools/fetch-opengothic.sh.
val engineCMake = rootProject.file("third_party/opengothic/CMakeLists.txt")
if (!engineCMake.isFile) {
    throw GradleException("Нет исходников движка: запусти tools/fetch-opengothic.sh")
}

// Свежие заголовки Vulkan (движку нужен VK_EXT_descriptor_heap, в NDK и Ubuntu заголовки старые):
// vulkan.sdk в local.properties, переменная VULKAN_SDK или /opt/vulkan-sdk (CLAUDE.md, «Сборка»).
val vulkanSdk: String = run {
    val localFile = rootProject.file("local.properties")
    val local = if (localFile.isFile) {
        Properties().apply { localFile.inputStream().use { load(it) } }.getProperty("vulkan.sdk")
    } else {
        null
    }
    local ?: System.getenv("VULKAN_SDK") ?: "/opt/vulkan-sdk"
}

// Номер версии растёт с каждым коммитом: новый APK ставится поверх старого.
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "gothic2.mobile.og"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // Тот же id, что у пробной сборки: обновление ставится поверх неё, сохранения и настройки
        // графики остаются (они в каталоге приложения).
        applicationId = "gothic2.mobile.og"
        minSdk = 28
        // Игра читается прямо из папки игрока по разрешению «Файлы» — старое хранилище, как у
        // targetSdk 28. Приложение ставится не из Google Play.
        targetSdk = 28
        // Последняя пробная сборка — 76.
        versionCode = 100 + commitCount
        versionName = "0.2.$commitCount"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_ANDROID_STL_TYPE=c++_static",
                    // Проект APK собирает Gradle, а не генератор Tempest (add_android_apk)
                    "-DTEMPEST_ANDROID_GRADLE_BUILD=ON",
                    "-DTEMPEST_VULKAN_SDK=$vulkanSdk",
                    // Отладочная сборка движка — сотни мегабайт и медленная игра: и в debug — Release
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                targets += "Gothic2Notr"
            }
        }
    }

    signingConfigs {
        // Общий для всех сборок debug-ключ (стандартный, не секретный): новый APK ставится поверх
        // старого, и сохранения остаются на телефоне.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = false
        }
        release {
            isMinifyEnabled = false
            // До настоящего ключа выпуска release тоже подписан общим debug-ключом.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = engineCMake
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        lintConfig = file("lint.xml")
    }
}

dependencies {
    testImplementation(libs.junit)
}

/**
 * Экран «Сведения о лицензиях»: тексты лицензий берутся из исходников самих компонентов движка —
 * копирайты в них точные, а при обновлении движка пропавший файл остановит сборку.
 */
abstract class GenerateLicenses : DefaultTask() {
    @get:Input
    abstract val names: ListProperty<String>

    /** Пути в том же порядке, что [names] (у набора файлов порядок не гарантирован). */
    @get:Input
    abstract val paths: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val files: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val sb = StringBuilder()
        names.get().zip(paths.get()).forEach { (name, path) ->
            sb.append("==== ").append(name).append(" ====\n\n").append(File(path).readText()).append("\n\n")
        }
        val dir = outputDir.get().asFile.resolve("licenses")
        dir.mkdirs()
        dir.resolve("third_party.txt").writeText(sb.toString())
    }
}

// Компонент → файл лицензии в third_party/opengothic (порядок важен: имена и файлы идут парами)
val engineLicenses = listOf(
    "OpenGothic (движок, MIT; изменён — правки проекта в third_party/opengothic.patch)" to "LICENSE",
    "Tempest (графика и окно, MIT; изменён)" to "lib/Tempest/LICENSE",
    "ZenKit (форматы файлов Gothic, MIT)" to "lib/ZenKit/license.md",
    "dmusic (музыка DirectMusic, MIT)" to "lib/dmusic/LICENSE.md",
    "Bullet Physics (физика, zlib)" to "lib/bullet3/LICENSE.txt",
    "TinySoundFont (MIT)" to "lib/TinySoundFont/LICENSE",
    "RapidJSON (MIT)" to "lib/rapidjson/license.txt",
    "miniz (MIT)" to "lib/miniz/LICENSE",
    "libsquish (MIT)" to "lib/ZenKit/vendor/libsquish/LICENSE.txt",
    "libpng" to "lib/Tempest/Engine/thirdparty/libpng/libpng/LICENSE",
    "zlib" to "lib/Tempest/Engine/thirdparty/zlib/LICENSE",
    "SPIRV-Cross (Apache-2.0)" to "lib/Tempest/Engine/thirdparty/spirv_cross/LICENSE",
    "Ogg/Vorbis — minivorbis (BSD-3-Clause)" to "lib/Tempest/Engine/thirdparty/minivorbis/minivorbis/LICENSE.txt",
    "OpenAL Soft (звук, LGPL-2.0-or-later; исходники — github.com/kcat/openal-soft)" to
        "lib/Tempest/Engine/thirdparty/openal-soft/COPYING",
)
val generateLicenses = tasks.register<GenerateLicenses>("generateLicenses") {
    val sources = engineLicenses.map { rootProject.file("third_party/opengothic/${it.second}") }
    names.set(engineLicenses.map { it.first })
    paths.set(sources.map { it.absolutePath })
    files.from(sources)
    outputDir.set(layout.buildDirectory.dir("generated/licenses"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateLicenses, GenerateLicenses::outputDir)
    }
}
