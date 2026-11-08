# Releases

APKs are published on [GitHub Releases](https://github.com/Sr-0w/Dot-Mail/releases), built from the tagged
commit and signed with the project key. Verify before installing:

```
apksigner verify --print-certs DotMail-<version>.apk
# Signer #1 certificate SHA-256 digest must be:
# 12c122aa367a5d0a0abf8f29e92fe28dfad37cf0373997494960e43391f7c81f4
sha256sum -c DotMail-<version>.apk.sha256
```

Pre-releases (`-alpha`, `-beta`) are for testing; expect rough edges. Debug builds and release builds are signed
with different keys, so switching between them requires uninstalling (which deletes local mail cache and
saved passwords — your mail stays on your server).

## Reproducible builds

Goal: rebuilding the release APK from the tag must produce a byte-identical APK (after the signing step) to
the published one. Everything below is read from the repo as of this writing — **verify against the tag you
are testing**.

### Exact toolchain

| Piece | Version | Where it is pinned |
|---|---|---|
| Gradle | 8.11.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.7.3 | `gradle/libs.versions.toml` (`agp`) |
| Kotlin | 2.1.0 | `gradle/libs.versions.toml` (`kotlin`) |
| KSP | 2.1.0-1.0.29 | `gradle/libs.versions.toml` (`ksp`) |
| JDK | 17 | `compileOptions` + `kotlinOptions.jvmTarget` in each module's `build.gradle.kts` (the Gradle wrapper does **not** pin the JDK — use the same JDK on the same major version) |
| compileSdk / targetSdk | 35 / 35 | `app/build.gradle.kts` |
| minSdk | 26 | `app/build.gradle.kts` |
| Build-Tools | not pinned in the repo — AGP 8.7.3 default is 34.0.0 for compilation; `scripts/release.sh` uses the **latest installed** build-tools for `zipalign`/`apksigner` | see "Recommended changes" |
| Dependencies | all pinned in `gradle/libs.versions.toml` (Compose BOM 2025.06.01, core-ktx 1.15.0, room 2.6.1, …) | `gradle/libs.versions.toml` |
| Build flags | `org.gradle.workers.max=2`, `org.gradle.parallel=false`, `org.gradle.caching=true`, `-Xmx2g -Dfile.encoding=UTF-8` | `gradle.properties` |

The release build is minified (`isMinifyEnabled = true` + R8 with `app/proguard-rules.pro`) and
`isShrinkResources = true`; it is built **debug-signed** (`signingConfig = debug`), then `scripts/release.sh`
zipaligns and signs it with the project key (`~/.config/dotmail` on the build host — never in the repo).

### Rebuild from the tag

```
git clone https://github.com/Sr-0w/Dot-Mail.git && cd Dot-Mail
git checkout v0.1.0-alpha.1
scripts/gw :app:assembleRelease        # builds on the registered build host
# unsigned (debug-signed) APK on the host:
#   <host-repo>/app/build/outputs/apk/release/app-release.apk
```

### Comparing with the published APK

**Option A — byte-exact, requires the signing key.** Re-run the signing pipeline from `scripts/release.sh`
on your rebuild (`zipalign -f -p 4` with the *same* build-tools version, then `apksigner sign` with the same
key/alias) and compare `sha256sum` against `DotMail-<tag>.apk`. `zipalign`/`apksigner` output is
deterministic for identical input + identical tool versions.

**Option B — signature-stripped, no key needed.** Compare every entry's content, ignoring the signature
artifacts (the v1 `META-INF` signature files and the APK signing block are expected to differ):

```
work() { d=$(mktemp -d); unzip -q "$1" -d "$d";
  find "$d" -type f ! -path "$d/META-INF/*" -print0 | sort -z | xargs -0 sha256sum | sed "s|$d/||"; }
diff <(work rebuilt.apk) <(work published-DotMail-<tag>.apk)
```

**Option C — diffoscope.** `diffoscope --exclude 'META-INF/.*' rebuilt.apk published-DotMail-<tag>.apk`
for a readable tree; any diff outside the signature block is real non-determinism to chase.

### Known sources of non-determinism — check in this order

1. **ZIP entry timestamps.** `unzip -l` both APKs: AAPT2 writes build-time stamps into the APK's zip
   entries. If they differ, that is (likely) *the* cause; normalise by re-packing with fixed timestamps
   (e.g. `TZ=UTC` + `touch -d @0` on the unpacked tree, `zip -X` repack) and see if Option B passes.
2. **R8 output.** Deterministic for identical AGP + identical inputs; the `mapping.txt` is not shipped in
   the APK, but class/method reordering can change bytecode. Keep AGP pinned (8.7.3) and proguard files
   committed (they are: `app/proguard-rules.pro`).
3. **VCS info.** `android.vcsInfo` is set in **no** module → R8 embeds no git stamp. Keep it off.
4. **dependenciesInfo.** `app/build.gradle.kts` already sets `includeInApk = false; includeInBundle = false`
   (required for F-Droid; no `assets/androidx.properties` in the APK). Verified — keep it.
5. **Baseline profiles.** None in the repo (no baseline-profile plugin or `.bp`/`.txt` files anywhere).
   If one is ever added, check the profile files in and pin the generator version.
6. **JDK.** Use the same JDK major version (17) for rebuilds; the wrapper does not pin it.
7. **SDK / build-tools.** The `compileSdk 35` platform revision must match; `release.sh`'s
   `zipalign`/`apksigner` use the newest installed build-tools (`ls -d $ANDROID_HOME/build-tools/* | sort -V | tail -1`)
   — a host SDK upgrade changes Option A's input (irrelevant for Option B).
8. **Locale/encoding.** `gradle.properties` forces `-Dfile.encoding=UTF-8` for the daemon.

### Recommended changes (NOT made by this doc — follow-up task)

1. Pin `buildToolsVersion = "34.0.0"` (the AGP 8.7.3 default) in all four modules so aapt2/zipalign inputs
   are explicit.
2. Make `scripts/release.sh` use a pinned build-tools directory instead of "latest installed".
3. Add a `reprotest` script/task: build the tag twice on the same host and compare (catches host drift),
   then optionally cross-host (catches real non-determinism).
4. If step 1 of the checklist above shows timestamp variance, add a zip normalisation post-step to the
   release pipeline (or track an upstream AGP reproducible-zip option).
5. Keep `vcsInfo` off and `dependenciesInfo` `includeInApk/includeInBundle = false` (both already the case).

