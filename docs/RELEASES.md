# Releases and rebuilds

The maintainer publishes tagged APKs through [GitHub Releases](https://github.com/Sr-0w/Dot-Mail/releases). This readiness work does not create a tag, sign an APK or publish a release. The authoritative public project certificate fingerprint is [release-certificate.sha256](release-certificate.sha256); secret signing material is external to this repository.

## Current build inputs

Read the exact tag before rebuilding. At this candidate the pins are:

| Input | Value / source |
|---|---|
| Gradle | 9.9.0; wrapper distribution SHA-256 pinned in gradle-wrapper.properties |
| AGP / Kotlin / KSP | 9.5.1 / 2.4.21 / 2.3.13; gradle/libs.versions.toml |
| Reference JDK | OpenJDK 21; record the full patch/vendor from the build log |
| Java/Kotlin output target | 17; this is not the Gradle runtime JDK |
| compile / target / minimum SDK | 37 / 35 / 26 |
| Build Tools | 36.0.0; Gradle and signing/alignment tooling agree |
| Dependencies | Exact catalog versions plus module declarations; no dynamic version policy introduced |
| R8 | Minification and resource shrinking enabled, with committed rules and upstream consumer rules |
| OAuth | Public client-ID properties must match the target binary; absence changes BuildConfig/redirect scheme. First release: Gmail Testing only; Microsoft OAuth excluded |

Keep SDK platform revisions, JDK and public OAuth inputs consistent when comparing builds. No signing password is a build input. Do not dump the host's Gradle properties or environment into a report.

Gmail OAuth remains in Google Testing mode (listed test users only, 7-day grants) until restricted-scope verification. Where permitted by the account, a Gmail app password supports IMAP/SMTP instead. Microsoft/Outlook OAuth is excluded from the first release.

Release Gradle builds now produce `app-release-unsigned.apk`. Developer builds still use their normal debug signing. The published project APK is signed externally; an F-Droid channel may use its own key. Different certificate identities are different Android update channels: never overwrite the owner's installation or recommend clearing its data as part of a release check.

## Reproduce an exact commit

Builds use `scripts/gw`. The reproducibility helper creates two fresh clones on the registered build host and calls that same wrapper in local mode **on the host only**. Do not set DOTMAIL_LOCAL=1 on the laptop.

```sh
scripts/reprotest.sh <published-tag-or-commit-ref>
# Optional comparison against an already published asset of the SAME commit:
scripts/reprotest.sh v0.1.0-alpha.2 --published v0.1.0-alpha.2
```

The ref must resolve locally and be available in the public repository; a pushed candidate branch is sufficient. Uncommitted changes are not included. Both builds disable Gradle build/configuration caches and persistent Gradle daemons, use different paths/time zones/umasks, and preserve the fresh checkout's Git provenance. Dependencies/distributions may come from the shared download cache. No normal host worktree, release key or phone is used.

Evidence is stored in a unique `build-out/reprotest/<commit>.<run>/` directory: APKs, build logs, toolchain pins, per-build inputs and JSON comparison. PASS requires **whole-file SHA-256 equality**, including ZIP metadata. Matching uncompressed files with different ZIP ordering/timestamps is not a reproducible APK. Historical tags may emit the old debug-signed artifact; each inputs file identifies that legacy mode. Their result is not proof that a release can be rebuilt without the debug signing input.

`scripts/apk_compare.py` compares every APK entry, including META-INF services, Jakarta Mail providers and Kotlin metadata. It rejects duplicate entry names. Its optional `--ignore-v1-signatures` mode is only a content diagnostic and labels its result CONTENTS_MATCH_ONLY. It never treats all META-INF files as signatures.

For F-Droid-style verification against an upstream signed APK, copy/verify signatures with `apksigcopier compare <signed.apk> <unsigned-rebuild.apk>` (the helper uses it when installed). Content-only diagnostics cannot prove this. [F-Droid's reproducible-build documentation](https://f-droid.org/docs/Reproducible_Builds/) explains signature-copy verification. Keep the `Binaries` metadata field unset until the actual tagged upstream artifact passes that check with the declared build inputs.

AGP can embed VCS information by default. Preserve the same clean commit in both source checkouts rather than assuming an unset vcsInfo property disables provenance. Compare baseline profiles, DEX, resources, providers and ZIP metadata; do not normalize/repack a failing APK merely to obtain a passing report. See the [AGP VcsInfo reference](https://developer.android.com/reference/tools/gradle-api/9.0/com/android/build/api/dsl/VcsInfo).

## Publish, only after maintainer review

1. Choose the next versionName/versionCode and update Gradle. Current alpha.2's code is already published; do not reuse it for a new release.
2. Review CHANGELOG.md's Unreleased section, then copy the reviewed localized notes from docs/releases/next into `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` (at most 500 characters). Preserve historical notes.
3. Run scripts/validate_store_metadata.py and the relevant scripts/gw checks, including release/R8 and verifyReleaseNotices. Review/regenerate the bundled third-party notices when dependencies change (THIRD_PARTY_NOTICES.md). Use demo fixtures only for screenshots.
4. Commit and merge the exact candidate, then rebuild/compare the final tagged source. A PR's earlier reproducibility result does not attest a later squash commit or different OAuth configuration.
5. Follow [SIGNING.md](SIGNING.md). `scripts/release.sh <tag> [--prerelease]` is a publishing command: it checks version/tag state, runs tests, pushes a tag, builds from a fresh clone, aligns/verifies and signs externally, checks the public certificate, then uploads assets. It is never a readiness test or PR hook.
6. Have the lead perform the approved signed-release device smoke checks. No phone installation is performed by the audit tooling.

Historical alpha.1/alpha.2 content-only comparisons used an older toolchain and ignored all META-INF entries. They are historical diagnostics, not evidence for this new strict unsigned-byte check or for today's candidate. New evidence belongs in the release-readiness report with its exact commit and inputs.
