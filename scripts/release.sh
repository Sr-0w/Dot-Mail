#!/usr/bin/env bash
# Builds a release APK on the build host FROM A FRESH CLONE OF THE TAG (no daemon, no build cache — the same way
# scripts/reprotest.sh rebuilds it, so anyone can reproduce the published file), signs it with the project key
# (~/.config/dotmail/release.jks on the host, never in the repo) and publishes a GitHub release.
#   scripts/release.sh v0.1.0-alpha.2 [--prerelease]
set -euo pipefail
TAG=${1:?tag like v0.1.0-alpha.2}; PRE=${2:-}
ROOT=$(cd "$(dirname "$0")/.." && pwd); HOST=${BUILD_HOST:-srobin@192.168.0.232}
REPO=${DOTMAIL_REPO:-https://github.com/Sr-0w/Dot-Mail.git}
cd "$ROOT"
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "working tree not clean"; exit 1; }
git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "HEAD is not origin/main — push or pull first"; exit 1; }

# 1. Tests on the current tree.
scripts/gw :mail:test :data:testDebugUnitTest :app:testDebugUnitTest

# 2. Tag and push, so the build below can only see committed, published sources.
git tag -a "$TAG" -m "Dot Mail $TAG" && git push -q origin "$TAG"

# 3. Clean build from the tag + sign.
WORK="/tmp/dotmail-release-$TAG"
ssh "$HOST" bash -s "$TAG" "$REPO" "$WORK" <<'REMOTE'
set -euo pipefail
tag=$1; url=$2; work=$3
set +u; . "$HOME/.bashrc" >/dev/null 2>&1 || true; set -u
set -a; . "$HOME/.config/dotmail/release-signing.env"; set +a
rm -rf "$work"; git clone -q --depth 1 --branch "$tag" "$url" "$work"
cd "$work"; echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --console=plain --no-daemon --no-build-cache -q :app:assembleRelease
cd app/build/outputs/apk/release
BT=$ANDROID_HOME/build-tools/35.0.0   # pinned, matches buildToolsVersion
"$BT/zipalign" -f -p 4 app-release.apk aligned.apk
"$BT/apksigner" sign --ks "$DOTMAIL_KEYSTORE" --ks-pass env:DOTMAIL_KEYSTORE_PASSWORD --ks-key-alias "$DOTMAIL_KEY_ALIAS" --out dotmail-signed.apk aligned.apk
"$BT/apksigner" verify --print-certs dotmail-signed.apk | grep -m1 SHA-256
REMOTE
mkdir -p build-out/release
scp -q "$HOST:$WORK/app/build/outputs/apk/release/dotmail-signed.apk" "build-out/release/DotMail-$TAG.apk"
ssh "$HOST" "rm -rf '$WORK'"
( cd build-out/release && sha256sum "DotMail-$TAG.apk" > "DotMail-$TAG.apk.sha256" )

# 4. Publish.
VC=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
NOTES=$(cat "fastlane/metadata/android/en-US/changelogs/$VC.txt" 2>/dev/null || echo "See README.")
gh release create "$TAG" "build-out/release/DotMail-$TAG.apk" "build-out/release/DotMail-$TAG.apk.sha256" \
  --title "Dot Mail $TAG" ${PRE:+--prerelease} --notes "$NOTES

Built from a clean clone of this tag; rebuild and compare with \`scripts/reprotest.sh $TAG\` (see docs/RELEASES.md).
Signing certificate SHA-256: 12:C1:22:AA:36:7A:5D:0A:0A:BF:8F:29:E9:2F:E2:8D:FA:D3:7C:F0:37:39:97:49:96:0E:43:39:1F:7C:81:F4
APK SHA-256: $(cut -d' ' -f1 "build-out/release/DotMail-$TAG.apk.sha256")"
