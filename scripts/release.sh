#!/usr/bin/env bash
# Builds a release APK on the build host FROM A FRESH CLONE OF THE TAG (no daemon, no build cache — the same way
# scripts/reprotest.sh rebuilds it, so anyone can reproduce the published file), signs it with the project key
# (~/.config/dotmail/release.jks on the host, never in the repo) and publishes a GitHub release.
#   scripts/release.sh v0.1.0-alpha.2 [--prerelease]
set -euo pipefail
TAG=${1:?tag like v0.1.0-alpha.2}; PRE=${2:-}
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]] || { echo "Invalid release tag" >&2; exit 1; }
[ -z "$PRE" ] || [ "$PRE" = --prerelease ] || { echo "Unexpected release option" >&2; exit 1; }
ROOT=$(cd "$(dirname "$0")/.." && pwd); HOST=${BUILD_HOST:-srobin@192.168.0.232}
REPO=${DOTMAIL_REPO:-https://github.com/Sr-0w/Dot-Mail.git}
cd "$ROOT"
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "working tree not clean"; exit 1; }
git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "HEAD is not origin/main — push or pull first"; exit 1; }

version_name=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' app/build.gradle.kts)
[ "${TAG#v}" = "$version_name" ] || { echo "Release tag does not match versionName" >&2; exit 1; }
! git show-ref --verify --quiet "refs/tags/$TAG" || { echo "Tag already exists" >&2; exit 1; }

VC=$(sed -n 's/^[[:space:]]*versionCode = \([0-9]*\)/\1/p' app/build.gradle.kts)
for lang in en-US fr-FR de-DE; do
  [ -f "fastlane/metadata/android/$lang/changelogs/$VC.txt" ] || { echo "Missing version-code changelog for $lang" >&2; exit 1; }
done
python3 scripts/validate_store_metadata.py

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
rm -rf "$work"; git clone -q --depth 1 --branch "$tag" "$url" "$work"
cd "$work"; echo "sdk.dir=$ANDROID_HOME" > local.properties
DOTMAIL_LOCAL=1 scripts/gw --console=plain --no-daemon --no-build-cache --no-configuration-cache -q :app:assembleRelease
cd app/build/outputs/apk/release
BT=$ANDROID_HOME/build-tools/36.0.0   # pinned, matches app/build.gradle.kts
"$BT/zipalign" -c -P 16 4 app-release-unsigned.apk
cp app-release-unsigned.apk aligned.apk
# Signing secrets are loaded only after Gradle has exited, never into the build environment.
set +x
umask 077
set -a; . "$HOME/.config/dotmail/release-signing.env"; set +a
: "${DOTMAIL_KEYSTORE:?external keystore required}" "${DOTMAIL_KEYSTORE_PASSWORD:?keystore password required}" "${DOTMAIL_KEY_ALIAS:?key alias required}"
sign_args=(--ks "$DOTMAIL_KEYSTORE" --ks-pass env:DOTMAIL_KEYSTORE_PASSWORD --ks-key-alias "$DOTMAIL_KEY_ALIAS")
if [ -n "${DOTMAIL_KEY_PASSWORD:-}" ]; then sign_args+=(--key-pass env:DOTMAIL_KEY_PASSWORD); fi
"$BT/apksigner" sign "${sign_args[@]}" --out dotmail-signed.apk aligned.apk
"$BT/apksigner" verify --verbose --print-certs dotmail-signed.apk > signing-verification.txt
fingerprint=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' signing-verification.txt | tr '[:upper:]' '[:lower:]' | tr -d ':')
expected=$(tr -d '[:space:]:' < "$work/docs/release-certificate.sha256")
[ "$fingerprint" = "$expected" ] || { echo "Signing certificate does not match the committed public certificate fingerprint" >&2; exit 1; }
printf '%s\n' "$fingerprint" > certificate.sha256
unset DOTMAIL_KEYSTORE_PASSWORD DOTMAIL_KEY_PASSWORD
REMOTE
mkdir -p build-out/release
scp -q "$HOST:$WORK/app/build/outputs/apk/release/dotmail-signed.apk" "build-out/release/DotMail-$TAG.apk"
scp -q "$HOST:$WORK/app/build/outputs/apk/release/certificate.sha256" "build-out/release/DotMail-$TAG.certificate.sha256"
ssh "$HOST" "rm -rf '$WORK'"
( cd build-out/release && sha256sum "DotMail-$TAG.apk" > "DotMail-$TAG.apk.sha256" )

# 4. Publish.
VC=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
NOTES=$(cat "fastlane/metadata/android/en-US/changelogs/$VC.txt" 2>/dev/null || echo "See README.")
gh release create "$TAG" "build-out/release/DotMail-$TAG.apk" "build-out/release/DotMail-$TAG.apk.sha256" \
  --title "Dot Mail $TAG" ${PRE:+--prerelease} --notes "$NOTES

Built from a clean clone of this tag; rebuild and compare with \`scripts/reprotest.sh $TAG\` (see docs/RELEASES.md).
Signing certificate SHA-256: $(cat "build-out/release/DotMail-$TAG.certificate.sha256")
APK SHA-256: $(cut -d' ' -f1 "build-out/release/DotMail-$TAG.apk.sha256")"
