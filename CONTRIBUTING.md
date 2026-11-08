# Contributing to Dot Mail

Dot Mail is a privacy-first, fully native (Kotlin + Jetpack Compose) open-source Android
email client. It is licensed under **GPL-3.0-or-later** — see `LICENSE`. By contributing
you warrant that your contribution is licensed under the same terms, and that you have
the right to contribute the code you submit.

## Prerequisites

- **JDK 17 or newer** (the build targets Java 17; JDK 21 works).
- **Android SDK 35** (`compileSdk` 35, `minSdk` 26, `targetSdk` 35).
  - Android Studio, or the command-line tools via the bundled helper:
    `./scripts/install-android-sdk.sh` (installs platform-tools, `platforms;android-35`,
    `build-tools;35.0.0`, the emulator and an x86_64 system image into `$ANDROID_HOME`
    or `~/Android/Sdk`).
- Gradle 8.11.1 (the wrapper downloads it; `./gradlew` is the entry point).

## Building

```sh
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads the Android Gradle plugin (8.7.x), Kotlin (2.1.x) and the
Compose toolchain, so it is slow; later builds are incremental.

> Note: `scripts/gw` in the repository is the internal remote-build helper used by the
> project's development agents (it syncs the tree to a build host). Regular contributors
> should just use `./gradlew` as above.

## Running tests

| Command | What it covers |
|---|---|
| `./gradlew :mail:test` | Mail engine, pure JVM: IMAP/SMTP against GreenMail, MIME, threading, HTML sanitizer, account autodiscovery (XML fixtures), plain-text helpers. |
| `./gradlew :data:testDebugUnitTest` | Data layer: repositories, Room (in-memory), outbox/state machine against a local GreenMail server (Robolectric). |
| `./gradlew :app:testDebugUnitTest` | App-level pure-JVM unit tests (e.g. `mailto:` parsing, address validation, design-system helpers). No Robolectric in `:app`. |
| `./gradlew :designsystem:testDebugUnitTest` | Design-system unit tests (none yet — components are covered by the app UI tests below). |
| `./gradlew :app:connectedDebugAndroidTest` | Compose UI tests on an emulator or device. They always run against the built-in **demo data set** (`DemoMailGraph`, no network) — see `app/src/androidTest`. |

Run everything: `./gradlew test connectedDebugAndroidTest` (the connected task needs a
running emulator/device).

**Live interop test (opt-in only).** `:mail` contains `LiveInfomaniakTest`, which talks to
a *real* mail account. It is skipped by default and only runs when you explicitly opt in
with `DOTMAIL_LIVE=1` and a credentials file at `~/.config/dotmail/test-account.env`.
**Never commit anything from `~/.config/dotmail/`** — it may contain real credentials.
Tests in the repository itself use fixtures and local (GreenMail) servers only.

## Code style

- Kotlin official style (`kotlin.code.style=official` in `gradle.properties`); 4-space
  indentation. There is no enforced ktlint/detekt configuration in the repo — match the
  surrounding code and keep diffs small.
- Keep `:mail` **pure JVM**: no `android.*` imports anywhere in that module.
- Public API goes in the contract files (below); internals stay `internal`.
- Timestamps are epoch milliseconds (UTC); JSON is plain `org.json` (see
  `data/.../Codec.kt`); no serialization framework is used on the data paths.

## Modules, ownership and boundaries

Four Gradle modules, strictly one-way dependencies:

```
:app  ->  :data  ->  :mail
:app  ->  :designsystem
```

| Module | Contents |
|---|---|
| `:mail` | Pure-JVM engine: IMAP/SMTP client (Jakarta Mail / Eclipse Angus), MIME parse/build, threading, HTML sanitizer, account autodiscovery, text helpers. No Android, no I/O beyond the protocols. |
| `:data` | Room database, repository implementations, WorkManager sync, outbox/undo-send, notifications, Android-Keystore credential vault. |
| `:designsystem` | The "Quiet" design language: tokens, theme, shared Compose components (buttons, top bars, inputs, avatars…). |
| `:app` | Compose UI: screens, ViewModels, navigation, `MainActivity`, manifest. Talks to `:data` only through the contract below. |

**The contract** (the seam between UI and data) is:

- `data/src/main/kotlin/app/dot/mail/data/model/Models.kt` — the shared domain models, and
- `data/src/main/kotlin/app/dot/mail/data/Repositories.kt` — the repository interfaces plus `MailGraph`.

The UI only sees those interfaces (obtained from `createMailGraph(context)` in
`DotMailApp.onCreate`; there is deliberately no DI framework). If you change the
contract, say so prominently in your PR — every consumer has to adapt.

Package roots: `app.dot.mail.engine` (`:mail`), `app.dot.mail.data` (`:data`),
`app.dot.design` (`:designsystem`), `app.dot.mail` (`:app`).

## Adding a provider to `BuiltInProviders`

The built-in table is the first step of account autodiscovery
(`mail/src/main/kotlin/app/dot/mail/engine/autoconfig/BuiltInProviders.kt`). To add a
provider:

1. **Verify the settings** before touching the table. Authoritative sources, in order:
   - Mozilla ISPDB: `https://autoconfig.thunderbird.net/v1.1/<domain>`
   - the provider's own autoconfig: `https://autoconfig.<domain>/mail/config-v1.1.xml`
     or `https://<domain>/.well-known/autoconfig/mail/config-v1.1.xml`
   - the provider's official documentation.
   Only list hosts you are certain of; unknown domains still work via the fallback chain
   (provider autoconfig → ISPDB → `imap.<domain>`/`smtp.<domain>` guess), so when in
   doubt, leave the domain out.
2. Add an `Entry` with `imapHost`/`imapPort`/`imapSocket` and the same for SMTP
   (prefer SSL on 993/465 where documented), the `providerName`, and:
   - `oauthRequired = true` when the provider disabled password sign-in (the setup UI
     then shows the provider hint and blocks the password flow),
   - `hint` when the user needs app-specific passwords (Gmail, Yahoo, iCloud…),
   - `usernameIsFullEmail = false` when the provider wants only the local part (web.de).
3. Register every known domain of that provider (lowercase, ASCII form).
4. Add a lookup test to `mail/src/test/kotlin/app/dot/mail/engine/autoconfig/AutoDiscoveryTest.kt`
   and run `./gradlew :mail:test`.

## Hard rules (product requirements, non-negotiable)

- **GPL-3.0-or-later** only. No new dependency without a FOSS licence compatible with it.
- **No telemetry, no analytics, no crash reporters.** Nothing leaves the device except
  the user's mail traffic to their own mail servers (and the one-time provider
  autodiscovery lookups, which contain only the domain, never the full email address).
- **No Google Play Services, no Firebase** (including FCM push).
- **Never send real user mail or credentials to any external service** — including AI
  APIs. Tests use fixtures and local GreenMail servers.
- **Never commit anything from `~/.config/dotmail/`** (live interop test credentials).
- Git: don't force-push `main`; larger changes go on a branch via PR (`gh pr create`)
  for review. Scan your diff for secrets before committing.

Questions? Read `docs/TEAM.md` (the team's working agreement, incl. architecture
decisions) and `docs/ARCHITECTURE.md` before asking.
