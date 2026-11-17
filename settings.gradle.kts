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

rootProject.name = "osmand-cell-plugin"

// :core        pure Kotlin on the JVM; everything that can be tested without Android
// :osmand-api  OsmAnd's AIDL client API, vendored (GPLv3)
// :app         the Android app
include(":core", ":osmand-api", ":app")
