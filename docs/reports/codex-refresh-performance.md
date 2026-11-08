# Refresh latency and connection recovery — 2026-11-08

## Live measurements

Opt-in read-only JVM/Robolectric test against the external Infomaniak test account (14 folders). Credentials stay in `~/.config/dotmail/test-account.env`, mode 600, outside the repository on the build host. No mailbox writes or phone access. Test: `DOTMAIL_LIVE=1 scripts/gw :data:testDebugUnitTest --tests '*LiveRefreshProfileTest*'`.

| Scenario | Before | After (two runs) |
|---|---:|---:|
| Complete warm account refresh, without existing IDLE lease | 17,118–18,253 ms | 3,022–3,051 ms |
| New protocol connections/logins | 29–30 | 4 |
| Complete warm refresh with warmed IDLE-owned pool | not reused | 1,188–1,248 ms; zero new connections/logins |
| Manual Inbox header/flag DB commit (spinner completion) | waited for whole account | 1,591–1,607 ms |
| Tail completion for that manual refresh | part of spinner wait | 3,018–3,027 ms |

Representative baseline cumulative phases: TLS/connect/greeting 9,018 ms, authentication 3,135 ms, UID inventory 1,136 ms, CONDSTORE 589 ms, SELECT 629 ms, DB writes/rebuilds 377 ms. Empty header-fetch work consumed 5,891 ms because its capability lookup opened another authenticated store-command connection for each selected folder. Phase timers can overlap: connect_login wraps initial socket/login, and SELECT/header work can create additional pooled protocols. They must not be added to derive wall time.

After: all 14 unchanged folders took the checkpoint fast path, with no UID inventory, header/flag FETCH, preview-body fetch or header DB rebuild. Fresh account scope used four logins (429 ms authentication, 1,230 ms TLS/connect/greeting cumulative); warmed IDLE scope needed none. Full message bodies are still fetched only on demand; bounded preview work is timed separately as snippet_bodies.

## Changes

- Authenticated IMAP store leases span an account sync and reuse an active IDLE owner. Angus retains up to four spare protocol connections and a separate store-command connection. Each selected folder owns its own protocol; cancelling IDLE closes only its read-only folder, not other borrowers' store. The last lease closes all pooled connections. Credentials/endpoints key leases, so accounts and reauthorization cannot cross.
- Up to two accounts and three tail folders per account run concurrently. Inbox and the selected mailbox run first. Queued MOVE source folders run before the parallel tail, with a final target pass when necessary to bind new UIDs safely.
- Skip unchanged folders only when UIDVALIDITY, UIDNEXT, EXISTS and a nonnegative HIGHESTMODSEQ all match. Servers without CONDSTORE retain full flag reconciliation; changed/reset/partial/error checkpoints never skip. Unchanged results never authorize deleting cached rows, including after a no-op STORE replay. Metadata/preview backfill and queued operations still run when needed.
- refresh(mailbox) completes after the requested folder/role's header+flag transaction commits; previews and remaining folders continue in graph scope. Category/Snoozed virtual views await the whole attempt. Late errors persist as account banners; a partial folder failure is not hidden by a successful Inbox response. Cancellation of the UI wait does not kill the background tail. Waits time out at 60 seconds.
- Read-only network attempts retry transient failures with bounded exponential backoff, with a longer initial pause for throttling. Authentication/certificate failures do not auto-retry. Mailbox writes and SMTP submissions are not blindly retried. Failed attempts no longer advance the account periodic-sync timestamp and suppress their own retry.

## UI contract

- `SyncState.Error.errorKind: app.dot.mail.engine.ConnectionFailure?` (default null): DNS, TIMEOUT, TLS, AUTH, SERVER_CLOSED, THROTTLED, NETWORK, OTHER.
- `SyncState.Error.message` is short human-safe text. `canRetry` is false for authentication errors (use existing sign-in), true otherwise.
- `suspend MailRepository.retrySync(accountId: Long)` retries that account's Inbox now and continues its tail in the background.
- Existing refresh signature is retained, with the primary-mailbox completion semantics above. Error metadata is encoded alongside the existing syncError string and decoded before UI exposure; legacy plain strings still work. No Room migration or new dependency.

Cause/nextException traversal is cycle-safe; sanitized logs preserve exception types and safe server replies. Classifier tests cover DNS, socket timeout, TLS/certificates, auth, BYE/EOF, throttling, network and cancellation. Typed fields never expose raw provider messages.

## ART method size

Parsed DEX code_item.insns_size from the current main APK and this debug APK. Main SyncKt.syncAll is exactly 60,636 code units, matching the owner's ART warning. It is now split by account and phase:

| Method | DEX code units |
|---|---:|
| syncAll | 412 |
| syncAccount | 716 (+ coroutine body 2,762) |
| syncOneFolder | 7,376 |
| replayFolderOperations | 7,028 |
| DB application coroutine | 2,106 |
| prefetchFolderPreviews | 718 |

No remaining refresh method is close to the original 60,636-unit state machine. Physical-device ART compilation/logging and actual phone timings remain Claude's smoke test; these server/JVM timings do not assert a measured phone latency.

## Validation

`scripts/gw :mail:test :data:testDebugUnitTest :app:assembleDebug` passed. Focused progress/error/move tests and the read-only live profile passed on the final phase split. GreenMail tests cover pooled concurrent/nested leases, IDLE cancellation with an active borrower, early return after committed Inbox rows, delayed previews, late folder failures, source/target MOVE ordering and body readability. Existing sync/flags/UIDVALIDITY/drafts/outbox suites stay green. Pure tests cover checkpoint safety and transient-only retries. Phone testing, HTML fit and the separately queued emoji snippet repair are not claimed complete here.
