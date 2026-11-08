# F-Droid metadata (draft)

Draft `fdroiddata` metadata for **`app.dot.mail`**, to be proposed in a fork of
<https://gitlab.com/fdroid/fdroiddata> (`metadata/app.dot.mail.yml`). Nothing here is filed yet —
t1/owner decides when to submit. No real mail data or credentials appear anywhere in this doc or in
the metadata.

## `metadata/app.dot.mail.yml`

```yaml
AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersionCode: 1
CurrentVersionName: "0.1.0"
Provides:
- action:android.intent.action.VIEW; scheme:mailto
- action:android.intent.action.SENDTO; scheme:mailto
- action:android.intent.action.SEND
- action:android.intent.action.SEND_MULTIPLE
License: GPL-3.0-or-later
SourceCode: https://github.com/Sr-0w/Dot-Mail
IssueTracker: https://github.com/Sr-0w/Dot-Mail/issues
AntiFeatures: [] # verified below
Builds:
- versionCode: 1
  versionName: "0.1.0"
  commit: v0.1.0-alpha.1
  gradle: yes
  # The Gradle project lives at the repo root (settings.gradle.kts); the
  # application module is `app/` — no `dir:` entry is needed (a `dir:` would
  # point at the module itself, which is not a standalone Gradle build).
  # No `sign:` entry: F-Droid signs with its own key.
```

Facts used above, and where they come from:

- `app.dot.mail` — `applicationId` in `app/build.gradle.kts`.
- `versionCode: 1` / `versionName: "0.1.0"` — same file (`defaultConfig`).
- `commit: v0.1.0-alpha.1` — the first public tag pushed by `scripts/release.sh`.
- `AutoUpdateMode: Version` — `versionName` changes per release.
- `UpdateCheckMode: Tags` — releases are git tags (`v*`).
- `Provides` — the `MainActivity` intent-filters in `app/src/main/AndroidManifest.xml`
  (VIEW + SENDTO with `scheme="mailto"`; SEND/SEND_MULTIPLE with `mimeType="*/*"`).
- `License: GPL-3.0-or-later` — the repo `LICENSE` is GPLv3; the "or later" clause follows the
  wording used in the store descriptions.
- `AntiFeatures: []` — verified below.
- `SourceCode`/`IssueTracker` — the project's GitHub repo; the F-Droid build clones the public
  tag, so nothing private is needed to build.

## Anti-features check

Expected: **none** — no tracking (the app only talks to the user's own mail servers, and remote
images / tracking pixels are blocked by default), no non-free dependencies, no ads, no
mandatory account. Verified by listing **every** dependency from `gradle/libs.versions.toml`:

| Dependency (version) | Ships in the APK? | Licence | Verdict |
|---|---|---|---|
| androidx.core:core-ktx (1.15.0) | yes | Apache-2.0 | free |
| androidx.activity:activity-compose (1.9.3) | yes | Apache-2.0 | free |
| androidx.lifecycle:* (2.8.7) | yes | Apache-2.0 | free |
| androidx.navigation:navigation-compose (2.8.5) | yes | Apache-2.0 | free |
| androidx.compose BOM 2025.06.01 → ui/foundation/material3/icons-extended/tooling-preview | yes | Apache-2.0 | free |
| androidx.room:room-* (2.6.1) | yes | Apache-2.0 | free |
| androidx.work:work-runtime-ktx (2.10.0) | yes | Apache-2.0 | free |
| androidx.paging:* (3.3.5) | yes | Apache-2.0 | free |
| androidx.biometric:biometric (1.1.0) | yes | Apache-2.0 | free |
| androidx.webkit:webkit (1.12.1) | yes | Apache-2.0 | free |
| androidx.glance:glance-appwidget (1.1.1) | yes | Apache-2.0 | free |
| org.jetbrains.kotlinx:kotlinx-coroutines-* (1.9.0) | yes | Apache-2.0 | free |
| org.jetbrains.kotlinx:kotlinx-serialization-json (1.7.3) | yes | Apache-2.0 | free |
| kotlin stdlib (2.1.0) | yes | Apache-2.0 | free |
| org.eclipse.angus:angus-mail (2.0.5) | yes | dual: EPL-2.0 / GPL-2.0 w/ Classpath exception | free (EPL-2.0 branch) |
| jakarta.mail:jakarta.mail-api (2.1.3) | yes | dual: EPL-2.0 / GPL-2.0 w/ Classpath exception | free (EPL-2.0 branch) |
| org.jsoup:jsoup (1.18.3) | yes | dual: ISC / MIT | free |
| com.caverock:androidsvg-aar (1.4) | yes | MPL-2.0 | free (weak copyleft, source-available) |
| com.mohamedrejeb.richeditor:richeditor-compose (1.0.0-rc13) | yes | Apache-2.0 | free |
| junit (4.13.2) | no — test only | EPL-1.0 | free |
| com.google.truth:truth (1.4.4) | no — test only | Apache-2.0 | free |
| com.icegreen:greenmail (2.1.2) | no — test only | Apache-2.0 | free |
| org.robolectric:robolectric (4.14.1) | not used (catalog entry only) | MIT | free |
| androidx.test:* (core/junit/ext 1.3.0/1.7.0), espresso (3.7.0), compose ui-test | no — test only | Apache-2.0 | free |
| AGP 8.7.3 / Kotlin 2.1.0 / KSP 2.1.0-1.0.29 | no — build tooling | Apache-2.0 (Kotlin plugin) / own terms (tooling, not shipped) | n/a |

No dependency opens a network connection other than the user's configured IMAP/SMTP endpoints;
there is no analytics, ad, push, or "account with us" code. Therefore `AntiFeatures: []` stands.

Re-check this table whenever `libs.versions.toml` gains a runtime dependency (especially anything
that fetches from the network: that is a `Networking` / potential `Tracking` review).

## Notes for the submission

- F-Droid builds from the public tag with its own toolchain: pinning issues will show up there
  first (see `docs/RELEASES.md` → "Recommended changes" for the build-tools/JDK pins).
- The app is debug-signed in-repo; the F-Droid build signs with the F-Droid key — no interaction.
- Suggested `AppIcon`/`Scrapshots` for the fdroiddata PR: the existing Play-store icon and
  screenshots in `fastlane/metadata/android/en-US/images/` (Apache-friendly, project-owned).
