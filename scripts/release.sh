#!/usr/bin/env bash
# Builds a release APK on the build host, signs it with the project key (~/.config/dotmail/release.jks,
# never in the repo), and publishes a GitHub release.   scripts/release.sh v0.1.0-alpha.1 [--prerelease]
set -euo pipefail
TAG=${1:?tag like v0.1.0-alpha.1}; PRE=${2:-}
ROOT=$(cd "$(dirname "$0")/.." && pwd); HOST=${BUILD_HOST:-srobin@192.168.0.232}
cd "$ROOT"
[ -z "$(git status --porcelain)" ] || { echo "working tree not clean"; exit 1; }
scripts/gw :mail:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleRelease
ssh "$HOST" 'source ~/.bashrc; set -e; set -a; . ~/.config/dotmail/release-signing.env; set +a
  cd ~/dotmail/app/build/outputs/apk/release
  rm -f aligned.apk dotmail-signed.apk; in=$(ls app-release*.apk | head -1)
  BT=$(ls -d $ANDROID_HOME/build-tools/* | sort -V | tail -1)
  $BT/zipalign -f -p 4 "$in" aligned.apk
  $BT/apksigner sign --ks "$DOTMAIL_KEYSTORE" --ks-pass env:DOTMAIL_KEYSTORE_PASSWORD --ks-key-alias "$DOTMAIL_KEY_ALIAS" --out dotmail-signed.apk aligned.apk
  $BT/apksigner verify --print-certs dotmail-signed.apk | grep -m1 SHA-256'
mkdir -p build-out/release
scp -q "$HOST:dotmail/app/build/outputs/apk/release/dotmail-signed.apk" "build-out/release/DotMail-$TAG.apk"
( cd build-out/release && sha256sum "DotMail-$TAG.apk" > "DotMail-$TAG.apk.sha256" )
VC=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
NOTES=$(cat "fastlane/metadata/android/en-US/changelogs/$VC.txt" 2>/dev/null || echo "See README.")
git tag -a "$TAG" -m "Dot Mail $TAG" && git push -q origin "$TAG"
gh release create "$TAG" "build-out/release/DotMail-$TAG.apk" "build-out/release/DotMail-$TAG.apk.sha256" \
  --title "Dot Mail $TAG" ${PRE:+--prerelease} --notes "$NOTES

Signing certificate SHA-256: 12:C1:22:AA:36:7A:5D:0A:0A:BF:8F:29:E9:2F:E2:8D:FA:D3:7C:F0:37:39:97:49:96:0E:43:39:1F:7C:81:F4
APK SHA-256: $(cut -d' ' -f1 "build-out/release/DotMail-$TAG.apk.sha256")"
