# Feature requests

The owner's list of things to add. **Add new items at the bottom of "Open"**, in any language, one line
is enough. The team reads this file whenever it picks the next piece of work, moves items to
"In progress" when started and to "Done" with a short note when shipped.

Priority hints (optional): `P1` = next, `P2` = soon (default), `P3` = nice to have.

## Open

- P2 · Allow users to disable conversation/threaded view and display individual emails as separate entries in the message list (configurable in Settings; preserve the choice across sessions).

- P2 · Animations throughout the app (screen transitions, list item enter/leave on archive/delete, swipe feedback, expand/collapse, shared-element-like transition list → conversation), and stylish custom loading spinners in the Quiet style. Not a priority.
- P2 · Sender profile pictures from other sources if possible (see avatar work in progress for what's covered).

- P1 · Offer an opt-in near-real-time background IMAP IDLE mode with clear battery/notification trade-offs, while preserving efficient periodic synchronization.
- P1 · Mirror drafts with the server's Drafts folder so edits are available across devices; handle offline edits and conflicts safely.
- P1 · Complete IMAP folder management: create, rename, delete and nest folders; reliably reconcile folders and flags across clients.

- P2 · Per-account and per-folder notification rules, including muting conversations and configurable notification actions.
- P2 · Optional native Android Contacts integration (contact picker/autocomplete/photos without requiring blanket contacts access).
- P2 · Advanced inbox sorting, filters and configurable smart folders across accounts.
- P2 · Dedicated attachment browser across accounts, with type/date/sender filters, full attachment search, preview, export and Android sharing.
- P2 · Backup, export, import and restore application settings/account configuration without exporting plaintext credentials by default.
- P2 · Complete localization (especially French and German), with proper plural forms, RTL support and locale-specific formats.
- P2 · Snooze/remind-later for individual messages: temporarily hide them and restore them at a selected time with optional local notification.
- P2 · Optional rules-based classification of personal mail, newsletters, purchases, receipts/bills and notifications; user-editable rules and no external processing.
- P2 · Strengthen anti-tracking and phishing protections: block remote resources and tracking pixels by default, optional sender allowlist, clear link destinations and optionally strip known tracking parameters with user control.
- P2 · PDF/document attachment preview with local text extraction where practical; convenient Android share-to-Paperless-ngx workflow, with any direct integration strictly optional.
- P2 · Reusable email templates and quick insertion in the native composer.
- P2 · Dedicated newsletter/mailing-list view, with safe RFC 2369/RFC 8058 List-Unsubscribe handling, confirmation and transparent behavior.
- P2 · Polished adaptive native layouts for tablets and foldables, including optional two-pane reading and hardware keyboard shortcuts.
- P2 · Adjustable message-list density, reading typography and accessibility; retain Quiet UI with responsive gestures, subtle haptics and smooth transitions.

- P3 · Optional integration with SimpleLogin and Addy.io for alias discovery/selection/management without dependence on either service.
- P3 · OpenPGP and S/MIME signing/encryption and verification using established, audited libraries or integrations.
- P3 · Optional JMAP support alongside IMAP/SMTP for compatible providers, without making JMAP mandatory.

## In progress

- P1 · Harden IMAP synchronization and message operations before daily-driver release: fix subject-only threading collisions, cross-folder archive/move behavior, large-body storage/performance, IDLE reconnect loops and global sync lock contention; add regression and network-failure tests. — Codex (file-backed bodies, IDLE churn, lock contention, threading — from Gemini's review)
- P1 · Complete OAuth2 account sign-in and token refresh for Google and Microsoft, without mandatory Google Play Services. — Codex (implemented, needs the project's own client IDs — see docs/OAUTH.md)
- P1 · Support multiple sender identities/aliases per account, with automatic reply identity selection and plain-text/HTML signatures. — Sonnet
- P1 · Enforce secure TLS during account discovery and credential verification (no silent plaintext fallback), and cover account setup with security regression tests. — Gemini (audit + regression tests)
- P1 · Ship properly signed release APKs with reproducible build guidance and end-to-end real-device validation before public distribution. — Qwen (CI + release workflows ready; waiting for the repo token's workflow scope)
- P2 · Native calendar invitation support for text/calendar and .ics: view event details, accept/tentatively accept/decline where supported, and add events via Android Calendar intents; prepare for future Dot Calendar integration. — Qwen (.ics parser first, UI next)
- P1 · Formatted (rich-text) emails — composer done (Gemini, Qwen); sending as multipart/alternative pending in the data layer (Codex)
- P1 · Pull-to-refresh arrow stuck on screen and not animated — fixed in the UI, verifying on device with the sync fix — Claude, Codex

## Done

- Configurable post-action navigation (e.g. after archiving/deleting an open message: next message, previous message or inbox). → Settings → General: after archive/delete go to next, previous or the list
- Complete settings screen (general, appearance, reading, composing, privacy, storage, about); per-account sync options pending data support (0.1)
- Colourful sender avatars + opt-in pictures: contact photos, Gravatar, brand logos (BIMI), website icons (0.1)
- Stylish Quiet spinners, dots loader, hairline progress, shimmer — design-system components; rolling out across screens (0.1)
- Dates in the message list were misaligned → pinned to the right edge, 24-hour clock follows the system setting (0.1)
- README that makes people want to try it, docs, regular commits on GitHub (0.1)
