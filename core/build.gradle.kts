// Pure Kotlin on the JVM: data model, parsers, estimator, tile math, GeoPackage
// encoding. No Android dependency, so `./gradlew :core:test` needs no SDK.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    // A real SQLite, to check that the GeoPackage schema is valid SQL and reads back.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}

tasks.test {
    useJUnitPlatform()
}
