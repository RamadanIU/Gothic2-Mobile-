// Среда исполнения Windows-программ: Winlator 11.2 (LGPL-2.1, https://github.com/brunodev85/winlator-app).
// Исходники не хранятся в репозитории: их скачивает tools/fetch-winlator.sh в third_party/winlator-app
// и накладывает third_party/winlator.patch. Здесь — только сборка как библиотеки для :app.
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

val winlatorSrc = rootProject.file("third_party/winlator-app/app/src/main")

android {
    namespace = "com.winlator"
    compileSdk = 35
    ndkVersion = "24.0.8215888"

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += "arm64-v8a"
        }
        consumerProguardFiles(rootProject.file("third_party/winlator-app/app/proguard-rules.pro"))
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile(File(winlatorSrc, "AndroidManifest.xml"))
            java.srcDir(File(winlatorSrc, "java"))
            res.srcDir(File(winlatorSrc, "res"))
            assets.srcDir(File(winlatorSrc, "assets"))
            jniLibs.srcDir(File(winlatorSrc, "jniLibs"))
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = File(winlatorSrc, "cpp/CMakeLists.txt")
        }
    }

    // Нативный код Winlator пишет кэш в /data/data/<пакет>/cache: пакет — как у :app этой сборки.
    val appId = providers.gradleProperty("gothic2.applicationId").get()
    val debugSuffix = providers.gradleProperty("gothic2.debugIdSuffix").get()
    buildTypes {
        getByName("debug") {
            externalNativeBuild { cmake { arguments += "-DAPP_PACKAGE_NAME=$appId$debugSuffix" } }
        }
        getByName("release") {
            externalNativeBuild { cmake { arguments += "-DAPP_PACKAGE_NAME=$appId" } }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Чужой код: его предупреждения не наша зона ответственности и не должны ронять сборку.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

/**
 * Список крупных архивов Winlator (*.tzst) с размерами и SHA-256 — assets/remote_assets.txt.
 * Сами архивы в APK не попадают (androidResources.ignoreAssetsPattern в :app): приложение
 * скачивает их с GitHub того же коммита и проверяет (com.winlator.core.RemoteAssets).
 */
abstract class RemoteAssetsManifest : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetsDir: DirectoryProperty

    @get:Input
    abstract val baseUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = assetsDir.get().asFile
        val lines = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tzst") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .map { f ->
                val digest = MessageDigest.getInstance("SHA-256")
                f.inputStream().buffered().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                "${f.relativeTo(root).invariantSeparatorsPath} ${f.length()} $hex"
            }
            .toList()
        val out = outputDir.get().asFile
        out.mkdirs()
        File(out, "remote_assets.txt").writeText((listOf("base ${baseUrl.get()}") + lines).joinToString("\n", postfix = "\n"))
    }
}

val winlatorCommit = providers.gradleProperty("winlator.commit").get()
val remoteAssetsManifest = tasks.register<RemoteAssetsManifest>("remoteAssetsManifest") {
    assetsDir.set(File(winlatorSrc, "assets"))
    baseUrl.set("https://raw.githubusercontent.com/brunodev85/winlator-app/$winlatorCommit/app/src/main/assets/")
    outputDir.set(layout.buildDirectory.dir("generated/remoteAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(remoteAssetsManifest, RemoteAssetsManifest::outputDir)
    }
}

dependencies {
    // Наследники XServerDisplayActivity в :app видят её суперклассы и интерфейсы.
    api(libs.androidx.appcompat)
    implementation(libs.androidx.preference.ktx)
    api(libs.material)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.xz)
    implementation(libs.commons.compress)
    implementation(libs.androidx.lifecycle.process)
}
