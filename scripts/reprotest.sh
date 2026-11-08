#!/usr/bin/env bash
# Reproducibility check for the release build (docs/RELEASES.md, "Reproducible
# builds"). Builds :app:assembleRelease twice from a git tag in two fresh
# checkouts on the build host — different absolute paths, different TZ and
# umask, no Gradle daemon, no build cache — then:
#   1. compares the two unsigned APKs entry by entry (sha256, META-INF
#      signature files ignored), and
#   2. compares the published signed APK from the GitHub release the same way
#      (zipalign and the APK signing block are not zip entry content, so no
#      signing key is ever needed).
# Reports land in build-out/reprotest/. Host temp dirs are removed on exit;
# ~/dotmail* (agent work dirs) are never touched.
#
# usage: scripts/reprotest.sh <git-tag>        e.g. scripts/reprotest.sh v0.1.0-alpha.1
# exit codes: 0 = PASS, 2 = DIFF, 1 = infrastructure failure
set -euo pipefail

TAG=${1:?usage: scripts/reprotest.sh <git-tag>}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
HOST=${BUILD_HOST:-srobin@192.168.0.232}
REPO=${DOTMAIL_REPO:-https://github.com/Sr-0w/Dot-Mail.git}
OUT="$ROOT/build-out/reprotest"
PUB="$OUT/DotMail-$TAG.apk"
HOSTOUT="$OUT/host-$TAG"
REPORT="$OUT/report-$TAG.txt"

mkdir -p "$OUT" "$HOSTOUT"

# Deterministic host work dir (fresh on each run); cleaned in the EXIT trap.
WORK="/tmp/dotmail-reprotest-$TAG"
cleanup() {
  ssh -q "$HOST" "rm -rf '$WORK'" || true
}
trap cleanup EXIT

log() {
  echo "[reprotest] $*"
}

# "sha256  relative/path" per APK entry, sorted, META-INF signature files excluded.
entry_hashes() {
  local apk=$1 d
  d=$(mktemp -d)
  unzip -q -o "$apk" -d "$d"
  (cd "$d" && find . -type f ! -path './META-INF/*' -print0 \
    | LC_ALL=C sort -z | xargs -0 -r sha256sum | sed 's|\./||')
  rm -rf "$d"
}

# ---------------------------------------------------------------------------
# Phase 1 — two fresh host builds.
# ---------------------------------------------------------------------------
log "cloning $TAG and building twice on $HOST (fresh dirs: $WORK/a, $WORK/b)"
ssh "$HOST" bash -s "$TAG" "$REPO" "$WORK" <<'REMOTE'
set -euo pipefail
tag=$1; url=$2; work=$3
rm -rf "$work"; mkdir -p "$work"
# shellcheck disable=SC1091
set +u; . "$HOME/.bashrc" >/dev/null 2>&1 || true; set -u
[ -n "${ANDROID_HOME:-}" ] || { echo "reprotest: ANDROID_HOME not set on host" >&2; exit 1; }

mkdir -p "$work/a" "$work/b"
git clone -q --depth 1 --branch "$tag" "$url" "$work/a/clone"
git clone -q --depth 1 --branch "$tag" "$url" "$work/b/clone"

run_build() { # $1=dir  $2=TZ  $3=umask
  local dir=$1 t0
  cd "$dir/clone"
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  t0=$(date +%s)
  TZ="$2" bash -c 'umask "$1"; shift; exec ./gradlew --console=plain --no-daemon --no-build-cache :app:assembleRelease' \
    _ "$3" > "$dir/build.log" 2>&1
  echo "$dir built in $(( $(date +%s) - t0 )) s (TZ=$2 umask=$3)" > "$dir/build-info.txt"
  cat "$dir/build-info.txt"
}

run_build "$work/a" "America/Anchorage" "022"
run_build "$work/b" "Asia/Tokyo" "077"

hash_entries() { # $1=apk  $2=out
  local d
  d=$(mktemp -d "$work/zip.XXXXXX")
  unzip -q -o "$1" -d "$d"
  (cd "$d" && find . -type f ! -path './META-INF/*' -print0 \
    | LC_ALL=C sort -z | xargs -0 -r sha256sum | sed 's|\./||') > "$2"
  rm -rf "$d"
}

meta_listing() { # $1=apk  $2=out
  unzip -l "$1" | awk 'NR>3 {n=$4; for (i=5; i<=NF; i++) n = n " " $i; print $1, $2, $3, n}' > "$2"
}

apka="$work/a/clone/app/build/outputs/apk/release/app-release.apk"
apkb="$work/b/clone/app/build/outputs/apk/release/app-release.apk"
[ -f "$apka" ] || { echo "reprotest: build A failed (log tail below)" >&2; tail -40 "$work/a/build.log" >&2; exit 1; }
[ -f "$apkb" ] || { echo "reprotest: build B failed (log tail below)" >&2; tail -40 "$work/b/build.log" >&2; exit 1; }

mkdir "$work/apks"
cp "$apka" "$work/apks/app-release-a.apk"
cp "$apkb" "$work/apks/app-release-b.apk"
cp "$work/a/build.log" "$work/apks/build-log-a.txt"
cp "$work/b/build.log" "$work/apks/build-log-b.txt"
hash_entries "$apka" "$work/hashes-a.txt"
hash_entries "$apkb" "$work/hashes-b.txt"
meta_listing "$apka" "$work/meta-a.txt"
meta_listing "$apkb" "$work/meta-b.txt"

{
  echo "host: $(hostname)"
  echo "workdir: $work"
  echo "java: $(java -version 2>&1 | head -1)"
  echo "ANDROID_HOME: $ANDROID_HOME"
  echo "gradle: $(grep -m1 distributionUrl "$work/a/clone/gradle/wrapper/gradle-wrapper.properties" | sed 's/.*gradle-//; s/-bin.zip.*//')"
  echo "host build-tools: $(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | tr '\n' ' ')"
  cat "$work/a/build-info.txt"
  cat "$work/b/build-info.txt"
} > "$work/host-info.txt"
echo "reprotest: host phase done (artifacts in $work)"
REMOTE

# ---------------------------------------------------------------------------
# Phase 2 — fetch host artifacts, download the published APK.
# ---------------------------------------------------------------------------
log "fetching host artifacts -> $HOSTOUT/"
scp -q "$HOST:$WORK/apks/app-release-a.apk" "$HOST:$WORK/apks/app-release-b.apk" \
       "$HOST:$WORK/apks/build-log-a.txt" "$HOST:$WORK/apks/build-log-b.txt" "$HOSTOUT/"
scp -q "$HOST:$WORK/hashes-a.txt" "$HOST:$WORK/hashes-b.txt" \
       "$HOST:$WORK/meta-a.txt" "$HOST:$WORK/meta-b.txt" \
       "$HOST:$WORK/host-info.txt" "$HOSTOUT/"

log "downloading published APK from the GitHub release -> $PUB"
if gh release download "$TAG" --pattern "DotMail-$TAG.apk" --dir "$OUT" >/dev/null 2>&1; then
  PUBLISHED=yes
else
  PUBLISHED=no
  log "published APK not available — skipping comparison 2"
fi

# ---------------------------------------------------------------------------
# Phase 3 — compare and report.
# ---------------------------------------------------------------------------
ha="$HOSTOUT/hashes-a.txt"
hb="$HOSTOUT/hashes-b.txt"

diff -u "$ha" "$hb" > "$OUT/diff-a-b.txt" || true
if [ ! -s "$OUT/diff-a-b.txt" ]; then
  VERDICT_AB="PASS"
else
  VERDICT_AB="DIFF ($(grep -c '^[+-][^+-]' "$OUT/diff-a-b.txt" || true) differing hash lines)"
fi

if [ "$PUBLISHED" = yes ]; then
  entry_hashes "$PUB" > "$OUT/hashes-published.txt"
  diff -u "$OUT/hashes-published.txt" "$ha" > "$OUT/diff-published-a.txt" || true
  if [ ! -s "$OUT/diff-published-a.txt" ]; then
    VERDICT_PUB="PASS"
  else
    VERDICT_PUB="DIFF ($(grep -c '^[+-][^+-]' "$OUT/diff-published-a.txt" || true) differing hash lines)"
  fi
else
  VERDICT_PUB="SKIPPED (no published APK)"
fi

diff -u "$HOSTOUT/meta-a.txt" "$HOSTOUT/meta-b.txt" > "$OUT/diff-meta-a-b.txt" || true
if [ -s "$OUT/diff-meta-a-b.txt" ]; then
  META_NOTE="zip metadata (timestamps/alignment) differs between A and B — see diff-meta-a-b.txt"
else
  META_NOTE="zip metadata identical between A and B"
fi

if [ "$VERDICT_AB" = "PASS" ] && [ "${VERDICT_PUB#DIFF}" = "$VERDICT_PUB" ]; then
  VERDICT="PASS"
else
  VERDICT="DIFF"
fi

{
  echo "reprotest report — $TAG"
  echo "date (UTC): $(date -u '+%Y-%m-%d %H:%M')"
  echo
  echo "host phase:"
  cat "$HOSTOUT/host-info.txt"
  echo
  echo "results:"
  echo "  A <-> B (two fresh host builds, different paths/TZ/umask): $VERDICT_AB"
  echo "  published <-> A (signatures stripped, no key needed):     $VERDICT_PUB"
  echo "  $META_NOTE"
  echo "  verdict: $VERDICT"
  echo
  echo "artifacts (this directory):"
  echo "  host-$TAG/app-release-a.apk, host-$TAG/app-release-b.apk  host builds"
  echo "  hashes-a.txt / hashes-b.txt / hashes-published.txt       per-entry sha256 (META-INF excluded)"
  echo "  diff-a-b.txt, diff-published-a.txt                       content diffs (empty = identical)"
  echo "  diff-meta-a-b.txt                                        unzip -l metadata diff (timestamps)"
  echo "  host-$TAG/build-log-a.txt / build-log-b.txt              full gradle output"
} > "$REPORT"
[ -s "$OUT/diff-a-b.txt" ] && { log "differing entries A vs B:"; sed -n '1,40p' "$OUT/diff-a-b.txt" >> "$REPORT"; }
[ -s "$OUT/diff-published-a.txt" ] && { log "differing entries published vs A:"; sed -n '1,40p' "$OUT/diff-published-a.txt" >> "$REPORT"; }
[ -s "$OUT/diff-meta-a-b.txt" ] && sed -n '1,40p' "$OUT/diff-meta-a-b.txt" >> "$REPORT"

# ---------------------------------------------------------------------------
# Phase 4 — summary.
# ---------------------------------------------------------------------------
log "=================================================================="
log "reprotest $TAG"
log "  A <-> B:      $VERDICT_AB"
log "  published:    $VERDICT_PUB"
log "  metadata:     $META_NOTE"
log "  verdict:      $VERDICT"
log "  report:       $REPORT"
log "=================================================================="
if [ "$VERDICT" = "DIFF" ]; then
  exit 2
fi
