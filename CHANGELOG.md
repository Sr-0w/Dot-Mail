# Changelog

## Unreleased

Changes merged after `v0.1.0-alpha.2`, through `a9313d6` (PR #77). This is a release-readiness candidate, not a published version; the maintainer chooses the next versionCode/tag.

### Mail reliability and offline data

- Faster refresh with shared IMAP sessions, Inbox-first completion, smaller sync phases and actionable connection errors (#34, #64).
- Unicode-safe snippets with repair of existing previews (#38); conservative threading, scoped thread actions preserving Sent copies, bounded sync metadata and historical thread repair (#67, #76).
- Full-content search, highlighting and server fallback (#42, #44, #53); correspondence filters include sent recipients and the permitted local address book (#55, #63).
- Per-account offline/download policies, cache quotas and Storage settings (#55, #62), alongside per-account sync settings (#14).

### Compose and accounts

- Continue with Google through browser OAuth, subject to configured client IDs/provider consent (#57).
- SimpleLogin/addy.io alias tools and sender identities (#47); richer recipient suggestions (#63).
- Custom scheduled-send dates/time zones, outbox editing and restart/clock-change recovery without blind duplicate sends (#59, #72, #73).
- Protected OpenPGP compose/outbox flows through OpenKeychain (#50); distinct verification/encryption indicators (#66).

### Native interface and reading

- English, French and German interfaces, RTL improvements and Settings search (#28, #43).
- Collapsing frosted headers, consistent neutral glass controls, electric-blue actions, rounded shape tokens and quieter progress controls (#32, #56, #70, #74, #75).
- Reader navigation tied to the source list, safer WebView/PDF teardown, responsive HTML fitting and remembered zoom (#65, #68, #77).
- Tracking-pixel blocking can be controlled independently of remote-image loading (#60).

### Build and internal work

- Toolchain updated to AGP 9.5.1, Gradle 9.9.0, Kotlin 2.4.21, compile SDK 37 and Room 2.8.5 (#70).
- Read-only JMAP engine and fixture tests behind an explicit MailClient facade (#79). Account integration remains future work; no JMAP setup or write operations are advertised.

## 0.1.0-alpha.2

Published historical notes remain in `fastlane/metadata/android/*/changelogs/2.txt` and the tagged source. Includes opt-in instant IMAP notifications and the earlier mail/folder/draft/reader foundations.
