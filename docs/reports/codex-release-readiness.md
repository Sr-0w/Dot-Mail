# Release readiness audit

Status: runtime/build checks, on-device R8 S/MIME check and clean-clone reproducibility green on codex/release-readiness (lead handover, after #101). No tag, release publication, signature operation, private signing-file read or phone access was performed by this audit. Initial UI capture source: main a9313d6; runtime audit rebased onto b1c2486, then documentation decision #86 (9cf2ea3). The JMAP core-table migration remains explicitly deferred after release (reference proposal in PR #80).

## Changes prepared

- Release Gradle payload is unsigned; the external project signing channel is preserved. Signing secrets are loaded only after Gradle exits. Alignment/tool versions agree at Build Tools 36.0.0; publication checks the existing committed public certificate fingerprint. Developer debug installs keep their previous behavior.
- Gradle 9.9.0's distribution SHA-256 is pinned from its official checksum endpoint. Compilation target 17 is distinguished from reference runtime JDK 21.
- Reprotest uses two fresh checkout paths, different time zones/umasks and no build/configuration caches. All Gradle invocations go through scripts/gw, using local mode only on the remote build host. It retains Git provenance and requires whole APK byte equality.
- APK comparison no longer masks all META-INF resources. Mail/activation services/providers and Kotlin metadata are compared; signature-only diagnostics are explicitly not reproducibility proof. Duplicate ZIP names and metadata-only differences have regression tests.
- F-Droid descriptions EN/FR/DE now describe actual offline/OAuth/PGP availability; historical changelogs are preserved. CHANGELOG.md summarizes merged PRs since alpha.2; localized next-version notes are prepared without guessing a version bump.
- Nine current native UI captures use only DemoMailGraph in isolated Robolectric Application contexts (EN/FR/DE, inbox/thread/settings). They are test renders, not device smoke evidence. Provenance and hashes are in fastlane/screenshots.json; validation checks all text lengths, allowed HTML, PNG dimensions and absence of text/EXIF payload metadata.
- RELEASES.md, SIGNING.md and FDROID.md describe the current toolchain, external signing, exact/diagnostic reproducibility distinction and distribution gates without any secret.

## Third-party notice blocker fixed

The initial inspection found license names in Settings but incomplete texts in the APK (META-INF duplicate exclusions and source-only font license). This was treated as a release blocker. The replacement is generated from all **206 resolved release runtime coordinates**, including transitives/BOMs, and scans binary JAR/AAR contents plus nested JARs. Complete upstream LICENSE/NOTICE/COPYING/COPYRIGHT texts retain source attribution; Apache-2.0 is shared, combined upstream texts remain intact. The bundle additionally carries Inter's copyright/OFL, project GPL and vendored OpenPGP license.

The checked-in assets are opened offline from Settings → Licences; IO runs off the main thread and text uses lazy selectable paragraphs. Every preReleaseBuild verifies exact resolved coordinate coverage and the reviewed bundle hash. Generation refuses undeclared or incomplete non-Apache licenses. Existing META-INF exclusions remain. Details and regeneration: [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). No new runtime dependency.

## Validation

- Python release tooling regression tests: 9 passed (strict APK comparison and notice generation, including nested AAR notices and refusal of incomplete non-Apache terms).
- Store metadata validator: all three locales / nine demo images passed.
- Demo capture fixture: 9 passed via scripts/gw; no real MailGraph or installed app.
- Shell syntax checks for release/repro scripts passed. The publishing/signing script itself is intentionally not invoked.
- Initial full tests/lint/R8 build passed (5m35s). Rebased full :mail:test / :data:testDebugUnitTest / :app:testDebugUnitTest / :app:lintRelease passed (3m56s): 455 mail tests (5 skipped), 228 data tests (4 skipped), 256 app tests (9 opt-in screenshot tests skipped), zero failures. Skips include live-account tests; no live account used.
- Initial shrunk artifact inspection passed: 10 persisted workers with unchanged names and public (Context, WorkerParameters) constructors; 24 META-INF service/mailcap/providers with matching DEX class definitions; Room database/implementation names; manifest components and 16-KiB native alignment. New final APK inspection also checks provider reflection constructors, route serializers and bundled notices.
- Focused LicenceNoticesTest (asset integrity/full notices and offline viewer) and release lint passed after fixing a test matcher that omitted a trailing newline. The focused tests passed (2/2, no skips), followed by lintRelease + unsigned assembleRelease (5m16s). Final shrunk-artifact audit passes the 206-component notices/hash, 10 persisted workers/constructors, 24 mail/activation providers/constructors, four route serializers, four Startup initializers/constructors, every merged-manifest component, Room reflection names and 16-KiB ELF/ZIP alignment.

- Negative coverage fixture: removed one runtime component from the build-host inventory, ran verifyReleaseNotices through scripts/gw and confirmed it failed with that missing coordinate; restored the exact original file in a finally block. No committed fixture/dependency or APK was altered by this check.
- First clean-clone attempt is **invalid evidence**: the audit operator edited reprotest.sh while that shell was running, causing a file-offset/resume error before artifact retrieval. No PASS or byte-difference claim is made for that run; its temporary clones were cleaned. The frozen final attempt targets c73e8a7fa056f27d3adc06171df02f78f71a4b52.

## Handover update: S/MIME ships (lead, after #101)

Sol (codex) ran out of quota; the lead finished this PR. Main now ships S/MIME (#84–#101), so Bouncy Castle is reachable and the evidence above was rerun on the rebased branch (`f6eaa96`, base `d991284`).

- **Release blocker found and fixed: BC under R8.** `BouncyCastleProvider` loads its algorithm tables (`…$Mappings`) and SPIs by class name. With the release R8 config the shrunk engine failed 4 of 5 on-device checks (`NoSuchAlgorithmException: no such algorithm: SHA256 for provider BC`), so S/MIME would have been broken in every release APK while debug builds worked. `app/proguard-rules.pro` now keeps names and constructors of `org.bouncycastle.jcajce.provider.**`, `pqc.jcajce.provider.**` and the provider class. The rest of BC shrinks normally. The APK grows from 6.4 MB to 7.7 MB.
- **On-device proof:** the `minifiedCheck` build type (release R8 config plus one entry point, never published) runs `SmimeR8Check` through `app_process` from `/data/local/tmp` on the S25 Ultra (no install, no app data). It covers sign + AES-256-CBC encrypt, decrypt, detached verify, tampered rejection, AES-GCM AuthEnvelopedData and opaque signed-data: **5/5 PASS** with the rules, 1/5 without. See docs/R8_AUDIT.md.
- **Notices:** regenerated from the rebased inventory (209 coordinates) and the shrunk footprint. bcprov, bcpkix and bcutil 1.86 are now `included`, with the Legion of the Bouncy Castle copyright and license text. Counts: 122 included, 7 proven stripped, 80 metadata-only. `verifyShippedNotices` passes on every assembleRelease.
- Full `:mail:test :data:testDebugUnitTest :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`: green. Release tooling: 21 Python tests OK. `check_r8_release.py`: 37 name-reached classes OK. `audit_release.py`: 10 workers, 26 providers, 4 serializers, 4 Startup initializers, 23 manifest components, ELF alignment, notices hash `56e37e70…`.
- `scripts/reprotest.sh f6eaa96`: two fresh clones on the build host give **byte-identical unsigned APK payloads (PASS)**. Later commits on this branch touch only docs and store text, not the APK inputs. The final tagged commit must still be rebuilt.
- Store descriptions and next-version notes (EN/FR/DE) no longer say S/MIME is excluded; they list it. CHANGELOG covers changes through #101.

## R8 scope and residual validation

No new blanket keep/dontwarn rules were needed at that time (before S/MIME shipped; see the handover update for the Bouncy Castle provider rules). Existing conservative Angus/Jakarta rules preserve provider/mailcap reflection, upstream WorkManager consumer rules preserve the other persisted worker names/constructors, Room's consumer rules preserve generated implementation lookup, and kotlinx.serialization consumer rules retain generated route serializers. App/data source scanning found no Gson/Moshi-style model reflection. JSON DOM/settings persistence is explicit; the typed navigation uses generated serializers. The APK inspection verifies actual DEX definitions and public reflection constructor prototypes, rather than assuming a successful shrink proves their presence.

NotificationActionReceiver and OutboxClockReceiver remain unexported, as does the special-use IDLE service; the exported WorkManager job service requires BIND_JOB_SERVICE. FileProvider remains unexported with explicit URI grants and source paths confined to files/attachments/. Startup includes WorkManager, process lifecycle, emoji and profile installer. Static artifact retention does not replace signed-device notification/restart/mail interoperability checks.

AGP emits its existing Compose stack-trace mapping warning for DotMailWidget.provideGlance; compilation/R8 succeeds, but that diagnostic mapping may be incomplete for this method. No warning is suppressed by this PR. No new runtime dependency is added. The resolved license inventory, rather than requested catalog versions alone, is authoritative (e.g. Jakarta Mail API resolves to 2.1.5 through Angus).

## Release/distribution gates

The next release version/code is a maintainer choice; alpha.2/code 2 is already published. Copy the reviewed next notes into the chosen numeric changelog files before tagging. The final tagged/squashed commit must be rebuilt and checked: a candidate's proof does not attest another commit or OAuth configuration. No F-Droid submission/Binaries claim is made without its own buildserver/signature-copy verification. First-release Microsoft/Outlook OAuth is explicitly excluded per #86. Gmail OAuth is limited to registered Google test users with 7-day grants until restricted-scope verification; app passwords are a fallback only when permitted by the account. Signed-device smoke remains lead-owned.
