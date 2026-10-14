import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "gothic2.mobile.app"
    compileSdk = 35

    defaultConfig {
        applicationId = providers.gradleProperty("gothic2.applicationId").get()
        // Android 10+: scoped storage.
        minSdk = 29
        // Как у Winlator: Wine, Box64 и их библиотеки исполняются из каталога данных приложения,
        // а Android запрещает это приложениям с targetSdk 29+. Приложение ставится не из Google Play.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-dev"
        ndk {
            // Только ARM64: среда исполнения (Box64 + Wine WoW64) не требует 32-битных ARM-процессов.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // Общий для всех сборок debug-ключ (стандартный, не секретный): новый APK ставится
        // поверх старого, и импортированная игра с сохранениями остаётся на телефоне.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = providers.gradleProperty("gothic2.debugIdSuffix").get()
            buildConfigField("boolean", "DEV_MODE_DEFAULT", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEV_MODE_DEFAULT", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        // Winlator запускает часть .so как программы (PulseAudio), поэтому они распаковываются на диск.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":winlator"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.preference.ktx)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}

android.lint {
    // Проверять и модуль core: он работает на Android, а не только на JVM (API уровня minSdk).
    checkDependencies = true
    abortOnError = true
    warningsAsErrors = false
    // Код Winlator (third_party) не проверяется; targetSdk 28 — осознанное решение (см. выше).
    lintConfig = file("lint.xml")
}
