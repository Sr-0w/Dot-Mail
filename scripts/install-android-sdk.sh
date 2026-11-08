#!/usr/bin/env bash
# Installs the Android SDK command-line tools and the packages Dot Mail needs.
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ZIP=commandlinetools-linux-16111833_latest.zip
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp=$(mktemp -d)
  curl -fL -o "$tmp/$ZIP" "https://dl.google.com/android/repository/$ZIP"
  unzip -q "$tmp/$ZIP" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SM" --sdk_root="$SDK" --licenses >/dev/null || true
"$SM" --sdk_root="$SDK" "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" "system-images;android-35;google_apis;x86_64"
echo "SDK_INSTALL_DONE"
