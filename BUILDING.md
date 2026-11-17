# Building

The project is three Gradle modules. `:core` is plain Kotlin on the JVM and holds
everything that can be tested without Android. `:osmand-api` is OsmAnd's AIDL
client API, vendored. `:app` is the Android app.

Toolchain: Gradle 8.11.1 (through the wrapper), Android Gradle Plugin 8.10.1,
Kotlin 2.1.20, JDK 17 to 23 (built with 21), Android platform 36 and
build-tools 36.0.0. minSdk is 29 (Android 10).

## Fast loop, no Android SDK needed

    ./gradlew :core:test

This works on a machine with no Android SDK installed (checked: with no
`local.properties` and no `ANDROID_HOME` the task still runs). It does need
network access to Google's Maven repository the first time, because Gradle
resolves the Android plugin even when it only runs `:core` tasks.

Gradle 8.11.1 runs on JDK 17 to 23. A newer JDK (25, say) as the only one
installed will refuse to start it; point `JAVA_HOME` at an older one.

The estimator tests take about a minute and a half; everything else is quick.
Two optional extras run only when asked:

    # The full model-comparison table (about 8 minutes)
    ESTIMATOR_COMPARE=1 ./gradlew :core:test --tests '*compareModels*' -i

    # A GeoPackage of synthetic data to open in QGIS
    SAMPLE_GPKG=/tmp/sample.gpkg ./gradlew :core:test --tests '*SampleGeoPackage*'

## Installing the Android SDK

`scripts/cloud-setup.sh` is the whole install: it downloads Google's
command-line tools, accepts the SDK licenses, installs the platform and
build-tools into `/opt/android-sdk` (override with `ANDROID_HOME`), and writes
`local.properties`. It takes about half a minute and needs `dl.google.com`.
Run it from the directory that contains the checkout:

    bash osmand-cell-plugin/scripts/cloud-setup.sh

The same file is meant to be pasted into a Claude Code cloud environment as its
setup script.

## Building the APK

    ./gradlew :app:assembleDebug

The result is `app/build/outputs/apk/debug/app-debug.apk`. Install it with

    adb install -r app-debug.apk

Debug builds are signed with `app/debug.keystore`, which is committed on purpose:
every build machine then signs with the same key, so a new build installs over
the old one and keeps its data. The key is public, so it says nothing about who
built an APK. Sign release builds with a private key instead.

## Getting an APK without building

`.github/workflows/build.yml` runs the tests and builds the debug APK on every
push, and attaches it to the run as the artifact `cell-coverage-debug-apk`
(GitHub wraps it in a zip; downloading needs a GitHub login).
