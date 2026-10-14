// Среда исполнения Windows-программ: Winlator 11.2 (LGPL-2.1, https://github.com/brunodev85/winlator-app).
// Исходники не хранятся в репозитории: их скачивает tools/fetch-winlator.sh в third_party/winlator-app
// и накладывает third_party/winlator.patch. Здесь — только сборка как библиотеки для :app.
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
