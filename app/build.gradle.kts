plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.hololeap.cellcoverage"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.hololeap.cellcoverage"
        // 29 is the first release with requestCellInfoUpdate and the 5G cell classes.
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        // A debug key kept in the repository, so that every build machine signs
        // with the same key and `adb install -r` keeps the app's data. It is
        // public, so it proves nothing about who built an APK.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":osmand-api"))
}
