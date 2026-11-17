// OsmAnd's AIDL client API, copied from osmandapp/OsmAnd (GPLv3).
// The sources under src/ are upstream's and are not edited here; only this
// build file is ours. src/ holds both the .java parcelables and the .aidl files.
plugins {
    id("com.android.library")
}

android {
    namespace = "net.osmand.aidlapi"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        aidl = true
    }

    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src"))
            aidl.setSrcDirs(listOf("src"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    api("androidx.annotation:annotation:1.9.1")
}
