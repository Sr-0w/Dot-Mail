# Changelog

## Unreleased

Changes merged after `v0.1.0-alpha.2`, through `d991284` (PR #101). This is a release-readiness candidate, not a published version; the maintainer chooses the next versionCode/tag.

### Mail reliability and offline data

- Faster refresh with shared IMAP sessions, Inbox-first completion, smaller sync phases and actionable connection errors (#34, #64).
- Unicode-safe snippets with repair of existing previews (#38); conservative threading, scoped thread actions preserving Sent copies, bounded sync metadata and historical thread repair (#67, #76).
- Full-content search, highlighting and server fallback (#42, #44, #53); correspondence filters include sent recipients and the permitted local address book; permission revocation, deduplication and trimmed addresses have regressions (#55, #63, #83, #85).
- Per-account offline/download policies, cache quotas and Storage settings (#55, #62), alongside per-account sync settings (#14).

### Compose and accounts

- Continue with Google through browser OAuth, verified on the owner’s device, but still limited to listed Google test users and 7-day grants until restricted-scope verification (#57, #86). Gmail app passwords remain a fallback where permitted; Microsoft OAuth is excluded from the first release.
- SimpleLogin/addy.io alias tools and sender identities (#47); richer recipient suggestions (#63).
- Custom scheduled-send dates/time zones, outbox editing and restart/clock-change recovery without blind duplicate sends (#59, #72, #73).
- Protected OpenPGP compose/outbox flows through OpenKeychain (#50); distinct verification/encryption indicators (#66).
- S/MIME with your own certificate from Android KeyChain (#84, #87, #92–#101): per-account signing/encryption certificates and defaults; Sign/Encrypt in Compose with per-recipient lock state, no silent downgrade when a certificate is missing, and one envelope for To+Cc with a separate one per Bcc recipient. Received mail shows its signature state (trusted only for a certificate chaining to a device CA and issued for the sender's address; certificate details on tap), detached and Outlook-style opaque signatures, and decrypts AES-CBC/GCM envelopes in memory, including attachments and inline images. Revocation is not checked (no OCSP/CRL requests), and this is stated in the details. Bouncy Castle 1.86 is the CMS library; its notices are bundled.

### Native interface and reading

- English, French and German interfaces, RTL improvements and Settings search (#28, #43).
- Collapsing frosted headers, consistent neutral glass controls, electric-blue actions, rounded shape tokens and quieter progress controls (#32, #56, #70, #74, #75).
- Reader navigation tied to the source list, safer WebView/PDF teardown, responsive HTML fitting and remembered zoom, safer long-message placement and guarded reader exits (#65, #68, #77, #78, #81, #82, #85).
- Tracking-pixel blocking can be controlled independently of remote-image loading (#60).

### Build and internal work

- Toolchain updated to AGP 9.5.1, Gradle 9.9.0, Kotlin 2.4.21, compile SDK 37 and Room 2.8.5 (#70).
- Localized provider hints, clearer empty-state actions and resource parity checks (#88, #89).
- Bouncy Castle provider kept under R8, verified on ART by the `minifiedCheck` S/MIME self-test; release notices are recomputed from the shrunk APK on every release build.
- Read-only JMAP engine and fixture tests behind an explicit MailClient facade (#79). Account integration remains future work; no JMAP setup or write operations are advertised.

## 0.1.0-alpha.2

Published historical notes remain in `fastlane/metadata/android/*/changelogs/2.txt` and the tagged source. Includes opt-in instant IMAP notifications and the earlier mail/folder/draft/reader foundations.
