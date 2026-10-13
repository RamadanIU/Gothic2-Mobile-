import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "gothic2.mobile.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "gothic2.mobile"
        // Android 10+: scoped storage и современные правила исполнения нативного кода.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        ndk {
            // Только ARM64: среда исполнения (Box64 + Wine WoW64) не требует 32-битных ARM-процессов.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
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
        // Несжатые и выровненные .so — требование устройств с 16 КБ страницами.
        jniLibs.useLegacyPackaging = false
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.preference.ktx)
}

android.lint {
    // Проверять и модуль core: он работает на Android, а не только на JVM (API уровня minSdk).
    checkDependencies = true
    abortOnError = true
    warningsAsErrors = false
}
