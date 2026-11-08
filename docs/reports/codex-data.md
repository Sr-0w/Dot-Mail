# Data layer implementation

Owner: Codex. No commits; :app/:designsystem untouched. Existing Models.kt / Repositories.kt contract unchanged.

## Delivered

* `createMailGraph(context)` creates a process singleton Room-backed implementation of AccountRepository, MailRepository and ComposeRepository. No DI framework. Local Flow/Paging reads; main-safe suspend functions.
* Room v1: accounts, folders/checkpoints, conversation rows and reference aliases, messages, attachments, drafts, outbox, pending operations, observed contacts. Foreign-key cascades remove account data. Exported schema retained under data/schemas.
* Password/token AES-GCM encryption using Android Keystore AES keys, random 96-bit IV, 128-bit tag and email-associated authenticated data. Only ciphertext in Room. Actual Keystore hardware behavior needs device acceptance; Robolectric tests inject a vault, not a fake claim of Keystore support.
* Account discovery delegates directly to Qwen's `AutoDiscovery`; verification checks IMAP and SMTP, classifies auth/certificate/connect errors. Identity defaults to the account's configured sender. Built-in XOAUTH2 transport works with a supplied token; refresh/consent UI remains v0.1's documented future work.
* Sync worker: constrained periodic work every 15 minutes, exponential retry; coalesced expedited one-shot work for refresh/writes. Foreground lifecycle observer opens one INBOX IDLE socket per account, closes sockets on stop, and reconnects with delay. No foreground service.
* Incremental header-only sync persists UIDVALIDITY/UIDNEXT/MODSEQ, applies flag changes, removes expunged cache entries, preserves optimistic changes while queued. Stale operations are retained with a UIDVALIDITY review error rather than applied to new UIDs. New mail notifications suppress the initial backfill.
* Thread grouping uses account-scoped Gmail/References/reply/Message-ID aliases, with subject fallback. Message-ID de-duplicates conversation counts across folders. Cached body is fetched only via loadBody; text/html and inline/attachment files are stored privately. A FileProvider exposes only the attachments subtree.
* Optimistic flag changes and moves are transactional. Moves preserve cached content in the destination with a temporary local UID, then reconcile to the server UID by Message-ID. Operations retain original source folder/UID/validity. Retry never uses a global EXPUNGE. Without UIDPLUS, fallback copies and marks deleted and surfaces the deferred-expunge status.
* Search grammar shared with IMAP: `from:`, `to:`, `subject:`, `has:attachment`, `is:unread`, `is:starred`; quoted phrases and free text. Local query predicates use bound values and literal LIKE escaping; null account includes all accounts. Server results merge into Room. Local body search works on downloaded text/HTML-derived text.
* Local draft autosave/recovery/reply/reply-all/forward; recipient suggestions from observed sender/To/Cc addresses. No contacts permission.
* At draft save and send, SAF/shared attachments are copied into private storage, retaining their display names; source-to-private mappings avoid recopying attachments on repeated autosaves. Temporary source URI grants can disappear without breaking the queued send. Aggregate outgoing attachment limit 25 MiB, at most 50 parts.
* Durable scheduled/undo outbox with transactional PENDING→SUBMITTING claim, cancellation CAS, persisted MIME and stable Message-ID. Future sends defer; preparation/connection failures retry. After successful SMTP: Gmail skips APPEND, other providers append to Sent. APPEND uses stable Message-ID search to avoid duplicate saved copies on retries. Worker replay does not resend an already completed message.
* SMTP DATA failures can be ambiguous. They become UNCERTAIN instead of automatically sending duplicates. A process death during SUBMITTING is likewise retained for review; an account error asks the user to check Sent. The current UI contract has no outbox-list/review API, so a dedicated recovery UI is still an integration follow-up. These states remain in Room and are never silently deleted.
* Per-account notification channel; private lock-screen visibility; POST_NOTIFICATIONS checked before posting; explicit nonexported receiver for Mark read / Archive. Notification content Intent launches the app with `dotmail.threadId` Long and unique thread URI.
* :data manifest provides INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, notification receiver and FileProvider. WorkManager's own merged manifest supplies its platform services/receivers; workers use the default factory and need no fake manifest component entries. Consumer R8 rules preserve Angus mailcap/providers, Activation and worker constructors.

## Verification

All execution uses `scripts/gw` on the build host. Final focused run:

```
scripts/gw :mail:test --tests app.dot.mail.engine.EngineTest --tests app.dot.mail.engine.HtmlSanitizerTest :data:testDebugUnitTest
```

**PASS: 11 engine tests and 10 Robolectric repository tests (zero failures; final remote build successful in 17s).**

Repository coverage: draft codec/persistence/undo/cascade, optimistic flags and reference threading, GreenMail sync with body-on-demand and replayed read flags, Android runtime MIME/Activation smoke, saved attachment after original file disappears, cancellation versus worker preparation CAS, local operator search and account filtering, scheduled deferral/SMTP/Sent append/idempotent replay, optimistic move UID reconciliation preserving body, UIDVALIDITY reuse with a pending read flag.

GreenMail's FROM SEARCH implementation uses exact InternetAddress equality rather than RFC substring matching; server fixtures use full email addresses, while production keeps the standard FromStringTerm. Local tests exercise substring addresses.

After Qwen’s fixes, full integration also passes: `scripts/gw :mail:test :data:testDebugUnitTest` — **99 mail tests + 10 data tests, zero failures** (remote BUILD SUCCESSFUL in 37s). This supersedes the earlier full-suite caveat.

## Limits / acceptance work

* No real-provider accounts/credentials were used. Gmail auto-Sent behavior, SPECIAL-USE/CONDSTORE, TLS certificate chains, physical-device Keystore, process/Doze scheduling, IDLE reconnect and OS notification actions require emulator/device/provider acceptance.
* WorkManager schedules opportunistically under Android restrictions; it cannot promise an exact send time.
* Drafts are locally authoritative; server draft APPEND is supported by the engine but data autosave does not currently mirror drafts to IMAP.
* Header-only lists intentionally have no body-derived snippets before a body is downloaded. Explicit body opening downloads all parts under the message-size ceiling; separate attachment streaming is future optimization.
* Folder-role actions require a discoverable target role (Archive can fall back to All Mail); permanent delete requires UIDPLUS.
* Only the configured account identity is persisted today; custom alias management needs contract/UI additions.
* Thread alias resolution does not yet union two already-existing conversations when a later message bridges them. Subject fallback can group unrelated messages sharing a subject.
* Attachment files are removed on account deletion. Per-message orphan-file eviction and attachment-cache quotas are future maintenance work.

Dependencies added: :data directly uses existing MIT jsoup for HTML-derived plain text, consumer ProGuard rules; no new external data-layer libraries. Engine adds Angus Activation/API as documented in codex-mail.md.
