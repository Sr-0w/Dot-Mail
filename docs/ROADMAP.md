# Roadmap

Honest and ordered. Each milestone ships an installable APK on GitHub Releases.

## 0.1 — "Daily driver" (in progress)

Goal: the owner can replace their current mail app for an IMAP account.

- [x] IMAP/SMTP engine, offline cache, queued actions, threads, search with operators
- [x] Autodiscovery incl. MX lookup for custom domains (e.g. a domain hosted at Infomaniak)
- [x] Outbox with undo send, scheduled send, duplicate-safe retries
- [x] Notifications with actions, home-screen widget, app shortcuts, app lock
- [x] Accessibility pass (contrast, touch targets, TalkBack, font scaling)
- [ ] Sync robustness fixes from real-device testing and the data-layer review
- [ ] **Formatted (rich-text) composing** with a plain-text option per message and a default in settings
- [ ] **Complete settings**: per-account (name, signature, sync frequency/window, notifications), reading, composing, privacy, storage
- [ ] **Colourful avatars and real sender pictures**: contact photos (opt-in), brand logos / website icons / Gravatar (each opt-in, off by default — they contact third parties)
- [ ] Signed release APK, reproducible build notes

## 0.2 — "Every provider"

- [ ] OAuth2 sign-in for Gmail and Microsoft (needs the project's own client registrations, see `docs/OAUTH.md`)
- [ ] Instant notifications mode (persistent IDLE in a foreground service, opt-in)
- [ ] Multiple identities / aliases per account, HTML signatures
- [ ] Drafts mirrored to the server's Drafts folder
- [ ] Folder management (create, rename, delete, nest)
- [ ] French and German translations
- [ ] Motion pass: purposeful animations across the app and custom Quiet-style loading spinners
- [ ] F-Droid metadata and submission

## 0.3 — "Power user"

- [ ] Filters and smart folders, newsletter view with one-tap unsubscribe
- [ ] Attachment browser and attachment search
- [ ] Conversation muting, per-folder notification rules
- [ ] OpenPGP (through OpenKeychain) and S/MIME
- [ ] Tablet / foldable two-pane layout

## Later — optional intelligence

Strictly opt-in, never required, never silent:

- Summaries of long threads and suggested replies, preferring on-device models, then a self-hosted
  endpoint configured by the user; cloud providers only as explicit opt-in extensions.
- Extraction of dates, tracking numbers and appointments into native intents (Calendar, Maps).

## Not planned

Ads, analytics, crash-reporting services, a Dot Mail cloud account, Google Play Services dependencies.
