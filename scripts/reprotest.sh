#!/usr/bin/env bash
# Two fresh, unsigned builds of a published commit/ref on the build host. No project release signing key is read.
# Every Gradle invocation uses scripts/gw (DOTMAIL_LOCAL=1 only inside the remote host).
# Usage: scripts/reprotest.sh <ref> [--published <release-tag>]
set -euo pipefail
REF=${1:?usage: scripts/reprotest.sh REF [--published TAG]}; shift
PUBLISHED_TAG=
if [ "${1:-}" = --published ]; then PUBLISHED_TAG=${2:?release tag}; shift 2; fi
[ "$#" = 0 ] || { echo 'Unexpected arguments' >&2; exit 1; }
ROOT=$(cd "$(dirname "$0")/.." && pwd)
HOST=${BUILD_HOST:-srobin@192.168.0.232}
REPO=${DOTMAIL_REPO:-https://github.com/Sr-0w/Dot-Mail.git}
SHA=$(git -C "$ROOT" rev-parse --verify --end-of-options "$REF^{commit}")
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || exit 1
if [ -n "$PUBLISHED_TAG" ]; then
  [[ "$PUBLISHED_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]] || exit 1
  [ "$(git -C "$ROOT" rev-parse --verify --end-of-options "$PUBLISHED_TAG^{commit}")" = "$SHA" ] || { echo 'Published tag must name the tested commit' >&2; exit 1; }
fi
mkdir -p "$ROOT/build-out/reprotest"
OUT=$(mktemp -d "$ROOT/build-out/reprotest/${SHA:0:12}.XXXXXXXX")
WORK=$(ssh "$HOST" 'mktemp -d /tmp/dotmail-repro.XXXXXXXX')
[[ "$WORK" =~ ^/tmp/dotmail-repro\.[A-Za-z0-9]+$ ]] || exit 1
cleanup() {
  ssh -q "$HOST" bash -s -- "$WORK" <<'REMOTE' || true
set -euo pipefail
[[ "$1" =~ ^/tmp/dotmail-repro\.[A-Za-z0-9]+$ ]] || exit 1
rm -rf -- "$1"
REMOTE
}
trap cleanup EXIT
printf 'Rebuilding commit %s; output: %s\n' "$SHA" "$OUT"
rc=0
ssh "$HOST" bash -s -- "$SHA" "$REPO" "$WORK" <<'REMOTE' || rc=$?
set -euo pipefail
sha=$1; repo=$2; work=$3
set +u; . "$HOME/.bashrc" >/dev/null 2>&1 || true; set -u
mkdir -p "$work/artifacts"
java -version > "$work/artifacts/java.txt" 2>&1
build() {
  local name=$1 zone=$2 mask=$3
  (
    umask "$mask"
    export TZ="$zone" LC_ALL=C.UTF-8
    git clone --quiet --no-checkout "$repo" "$work/$name"
    cd "$work/$name"
    git checkout --quiet --detach "$sha"
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
    # This wrapper runs locally ON THE BUILD HOST; never on the low-memory laptop.
    DOTMAIL_LOCAL=1 scripts/gw --no-daemon --no-build-cache --no-configuration-cache :app:assembleRelease
    apk=app/build/outputs/apk/release/app-release-unsigned.apk
    payload_kind=unsigned
    if [ ! -f "$apk" ] && [ -f app/build/outputs/apk/release/app-release.apk ]; then
      apk=app/build/outputs/apk/release/app-release.apk
      payload_kind=legacy-debug-signed
    fi
    [ -f "$apk" ] || { echo 'Release APK missing' >&2; exit 1; }
    cp "$apk" "$work/artifacts/$name.apk"
    cp app/build/outputs/apk/release/output-metadata.json "$work/artifacts/$name-metadata.json"
    cp gradle/wrapper/gradle-wrapper.properties "$work/artifacts/gradle-wrapper.properties"
    cp gradle/libs.versions.toml "$work/artifacts/libs.versions.toml"
    printf 'commit=%s\nTZ=%s\numask=%s\npayload=%s\n' "$sha" "$zone" "$mask" "$payload_kind" > "$work/artifacts/$name-inputs.txt"
  ) > "$work/artifacts/$name.log" 2>&1
}
build build-a America/Anchorage 022
build build-b Asia/Tokyo 077
REMOTE
# Keep logs even when a clean build fails. They contain no signing environment or credential dump.
rsync -az "$HOST:$WORK/artifacts/" "$OUT/"
[ "$rc" = 0 ] || { printf 'Clean build failed; logs: %s\n' "$OUT" >&2; exit 1; }
python3 "$ROOT/scripts/apk_compare.py" "$OUT/build-a.apk" "$OUT/build-b.apk" --report "$OUT/unsigned-comparison.json"
if [ -n "$PUBLISHED_TAG" ]; then
  gh release download "$PUBLISHED_TAG" --repo Sr-0w/Dot-Mail --pattern "DotMail-$PUBLISHED_TAG.apk" --dir "$OUT"
  python3 "$ROOT/scripts/apk_compare.py" "$OUT/build-a.apk" "$OUT/DotMail-$PUBLISHED_TAG.apk" \
    --ignore-v1-signatures --report "$OUT/published-content-diagnostic.json"
  if command -v apksigcopier >/dev/null 2>&1; then
    apksigcopier compare "$OUT/DotMail-$PUBLISHED_TAG.apk" "$OUT/build-a.apk" > "$OUT/signature-copy-check.txt" 2>&1
  else
    echo 'Signature-copy verification not performed: install apksigcopier for the F-Droid comparison.'
  fi
fi
printf 'PASS: fresh APK payloads are byte-identical (see each inputs file for signing mode). Evidence: %s\n' "$OUT"
