# F-Droid submission preparation

The source repository contains Fastlane metadata under `fastlane/metadata/android/`: EN/FR/DE titles, summaries, descriptions, version-code changelogs and demo-only images. Nothing in this readiness PR submits an app or publishes a repository. The maintainer decides the final release/tag and F-Droid submission.

Run `python3 scripts/validate_store_metadata.py`. It checks the [documented metadata format](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/): 50/80/4000-character title/summary/description limits, supported description tags, 500-character changelogs, valid PNG sizes and screenshot provenance. Historical numbered changelogs remain historical; prepared notes for the next code live in docs/releases/next.

## Images and privacy

Current screenshots are native Robolectric renders of the actual UI, using only DemoMailGraph and a plain isolated Application. No real database, account, credential, system notification, phone or network is involved. Mail content is synthetic English fixture text; UI labels are localized. The manifest `fastlane/screenshots.json` records the recipe, source UI revision, theme and each image's SHA-256. It deliberately identifies these as test renders, not device verification.

```sh
scripts/gw :app:testDebugUnitTest -Pdotmail.storeShots=true --tests '*StoreScreenshotsTest'
# Review build-out/reports/app/store-shots/<locale>/*.png before replacing store images.
```

Do not generate screenshots from the owner's account or install on the phone. Normal test runs skip the explicit capture fixture. If rendering changes, review each locale and regenerate the provenance hashes; never silently substitute real-mail captures.

## Build metadata draft

F-Droid imports the store text/images from the tagged source; graphics should not be put in fdroiddata itself. Use its [current metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/). The older draft used obsolete Provides and CurrentVersionName fields; those are removed here.

The following identifies the **historical alpha.2 tag**, not a new build of today's main. Update versionName, code and immutable commit/tag together for the selected release after checking its Gradle file:

```yaml
Categories:
  - Internet
License: GPL-3.0-or-later
SourceCode: https://github.com/Sr-0w/Dot-Mail
IssueTracker: https://github.com/Sr-0w/Dot-Mail/issues
Changelog: https://github.com/Sr-0w/Dot-Mail/blob/main/CHANGELOG.md
RepoType: git
Repo: https://github.com/Sr-0w/Dot-Mail.git

Builds:
  - versionName: 0.1.0-alpha.2
    versionCode: 2
    commit: v0.1.0-alpha.2
    gradle:
      - yes

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags
CurrentVersion: 0.1.0-alpha.2
CurrentVersionCode: 2
```

The multi-module Gradle settings are at the root; the application is :app. A real fdroidserver build is still required to validate the recipe on F-Droid's buildserver image. Today's source uses JDK 21, compile SDK 37 and Build Tools 36.0.0; historical alpha.2 has its own older pins. F-Droid signs an unsigned payload with its channel key unless a separately approved, signature-copy-verified upstream Binaries workflow is configured. See [RELEASES.md](RELEASES.md) and [SIGNING.md](SIGNING.md).

## Accurate feature and network disclosure

The descriptions distinguish header sync from downloaded offline content, configured OAuth from universal sign-in, and optional OpenKeychain from built-in OpenPGP. Google consent/client configuration and Microsoft registration remain release/distribution gates; a build without IDs must not claim browser sign-in. JMAP account setup and S/MIME are not advertised merely because engine code exists.

No Play Services, telemetry, advertising SDK or app-hosted mail intermediary is introduced. Outbound traffic is broader than IMAP/SMTP alone: account discovery can use autoconfig/ISPDB/DNS, browser OAuth contacts the chosen provider, explicitly enabled sender images/remote resources use their sources, and optional alias/unsubscribe tools contact selected endpoints. Do not repeat the old claim that every connection is only to the configured mail servers.

Declared dependency licenses and the resolved release runtime graph belong in the audit report. F-Droid maintainers evaluate anti-features and upstream binaries; this document does not assert that dependency-name inspection alone proves acceptance or that every optional network feature is automatically exempt. No AntiFeatures, Binaries or signing private material is silently submitted on the maintainer's behalf.
