# Release readiness audit

Status: in progress on codex/release-readiness. No tag, release publication, signature operation, private signing-file read or phone access is authorized/performed by this audit. Base UI/runtime source: main a9313d6. The JMAP core-table migration remains explicitly deferred after release (reference proposal in PR #80).

## Changes prepared

- Release Gradle payload is unsigned; the external project signing channel is preserved. Signing secrets are loaded only after Gradle exits. Alignment/tool versions agree at Build Tools 36.0.0; publication checks the existing committed public certificate fingerprint. Developer debug installs keep their previous behavior.
- Gradle 9.9.0's distribution SHA-256 is pinned from its official checksum endpoint. Compilation target 17 is distinguished from reference runtime JDK 21.
- Reprotest uses two fresh checkout paths, different time zones/umasks and no build/configuration caches. All Gradle invocations go through scripts/gw, using local mode only on the remote build host. It retains Git provenance and requires whole APK byte equality.
- APK comparison no longer masks all META-INF resources. Mail/activation services/providers and Kotlin metadata are compared; signature-only diagnostics are explicitly not reproducibility proof. Duplicate ZIP names and metadata-only differences have regression tests.
- F-Droid descriptions EN/FR/DE now describe actual offline/OAuth/PGP availability; historical changelogs are preserved. CHANGELOG.md summarizes merged PRs since alpha.2; localized next-version notes are prepared without guessing a version bump.
- Nine current native UI captures use only DemoMailGraph in isolated Robolectric Application contexts (EN/FR/DE, inbox/thread/settings). They are test renders, not device smoke evidence. Provenance and hashes are in fastlane/screenshots.json; validation checks all text lengths, allowed HTML, PNG dimensions and absence of text/EXIF payload metadata.
- RELEASES.md, SIGNING.md and FDROID.md describe the current toolchain, external signing, exact/diagnostic reproducibility distinction and distribution gates without any secret.

## Validation in progress

- Python APK comparator regression tests: 5 passed.
- Store metadata validator: all three locales / nine demo images passed.
- Demo capture fixture: 9 passed via scripts/gw; no real MailGraph or installed app.
- Shell syntax checks for release/repro scripts passed. The publishing/signing script itself is intentionally not invoked.
- Full release tests/lint/R8, merged-manifest/reflection/worker retention audit and clean two-build reproducibility: pending.

## Release/distribution gates

The next release version/code is a maintainer choice; alpha.2/code 2 is already published. Copy the reviewed next notes into the chosen numeric changelog files before tagging. The final tagged/squashed commit must be rebuilt and checked: a candidate's proof does not attest another commit or OAuth configuration. No F-Droid submission/Binaries claim is made without its own buildserver/signature-copy verification. Provider client registration/consent determines OAuth availability; no unconditional Gmail/Microsoft sign-in claim is made. Signed-device smoke remains lead-owned.
