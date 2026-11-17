#!/bin/bash
# Setup script for a Claude Code cloud environment: installs the Android SDK
# pieces this project builds with. Needs dl.google.com on the allowed domains.
# Running it accepts the Android SDK licenses.
set -e
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
TOOLS_ZIP=commandlinetools-linux-15859902_latest.zip
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -sSL -o "/tmp/$TOOLS_ZIP" "https://dl.google.com/android/repository/$TOOLS_ZIP"
  unzip -q -o "/tmp/$TOOLS_ZIP" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm "/tmp/$TOOLS_ZIP"
fi

yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
"$SDKMANAGER" "platforms;android-36" "build-tools;36.0.0" "platform-tools" > /dev/null

# Tell Gradle where the SDK is. The script starts in the directory that holds
# the checkout, so skip this when the repository is not there.
if [ -d osmand-cell-plugin ]; then
  echo "sdk.dir=$ANDROID_HOME" > osmand-cell-plugin/local.properties
fi
