# Storage/offline policy and correspondence filters — Sol

Implemented on `codex/work`, rebased through `23f521a` (including `1651d33` draft trash/APPENDUID and subsequent OpenPGP compose changes). Room advances from v8 to v9. No new dependencies. Settings UI and real-phone verification remain Claude's work.

## Public UI contract

All hooks live on `MailRepository`; types are in `model/StorageModels.kt`.

- `storagePolicy(accountId): Flow<StoragePolicy>` / `setStoragePolicy(accountId, policy)`.
- `globalStorageLimit: Flow<Long>` / `setGlobalStorageLimit(bytes)`; zero means unlimited, positive values must be at least 64 MiB.
- `offlineProgress(accountId): Flow<OfflineProgress>`; `pauseOfflineDownload(accountId)` / `resumeOfflineDownload(accountId)`.
- `setOfflineSaved(messageIds, saved)` / `offlineSavedMessages(): Flow<Set<Long>>`; these accept real message IDs, not conversation IDs.
- `storageUsage(): StorageUsage`; `clearCachedMail(accountId): CacheCleanupResult`.
- `refreshKnownContacts()` after a contacts-permission result. The existing Settings permission callback is already wired.

`StoragePolicy` selects `RetentionMode.LATEST_MESSAGES`, `LAST_DAYS`, or `ALL`, with `latestMessages`, `days`, `maxBytes`, `downloadBodies`, `downloadAttachments`, `wifiOnly`, and `chargingOnly`. Defaults are 30 days, 512 MiB, headers plus manually opened bodies, Wi-Fi only. An untouched account keeps its existing sync-window setting until a storage policy is configured. Full offline means ALL + downloadBodies=true; attachments are optional; maxBytes=0 removes the policy ceiling but never the device-space reserve. Attachment download requires body download.

Progress distinguishes IDLE, QUEUED, DOWNLOADING, PAUSED, WAITING_FOR_NETWORK, WAITING_FOR_CHARGING, STORAGE_LIMIT, DEVICE_FULL, COMPLETE, PARTIAL, and ERROR. Counts describe selected cached headers/bodies/attachments, not arbitrary old cache outside the policy. The age-window total remains unknown (zero) until enumeration finishes. Header parse failures and individual payload failures produce PARTIAL rather than false completion. Resume retries failed items; automatic retries have a cooldown. Pause and clear cancel both bulk and pin-download work. A later explicit pin may fetch its selected message while bulk remains paused.

Usage separates per-account body/attachment files, protected bytes, logical indexed-text bytes, shared SQLite/WAL/SHM bytes, outbox, draft attachments, orphan cache, and device availability. Indexed text is an informational logical estimate already included in physical database size; do not add it to totalBytes. `isApproximate` reports conservative bounded accounting. Per-account maxBytes governs payload files; the global limit includes the shared database and protected local work. Protected data may exceed a requested limit.

## Download and cleanup behavior

Durable backwards UID pages (200 maximum) enumerate beyond the initial header window. Checkpoints survive restart and resolve an expunged anchor to the preceding surviving UID. Two folder pages and eight bodies per pass keep work bounded and fair; WorkManager continuations avoid treating ordinary unfinished work as failures. New mail restarts completed scans from their previous upper boundary. Normal sync still reconciles server deletions/UIDVALIDITY.

BODYSTRUCTURE/PEEK downloads text/HTML and inline parts without downloading ordinary attachments. Attachment metadata remains available; opening one downloads only its part. Existing payloads and stable attachment IDs are reused. Streaming UTF-8 output and attachment writes enforce byte/part/depth limits and check cancellation. PGP signatures retain byte-exact canonical content; attachments inside an already downloaded signed entity are extracted locally, even in bodies-only mode. Encrypted MIME necessarily caches its ciphertext envelope.

Quota reservations include concurrent transfers and temporary-file headroom. A 64 MiB device reserve remains mandatory. LRU eviction runs only after connection/authentication succeeds; a failed connection cannot destroy useful offline cache. Automatic ALL downloads stop at the limit rather than cycling through eviction/redownload. Pinning, active readers, drafts, outbox references, queued changes, and unresurfaced snoozes protect payloads. Corrupt/oversized reference data conservatively protects the affected account. New TRASH_DRAFT keeps its DraftRow until replay, preserving its attachment references.

Clear pauses prefetch, removes eligible local payloads, and retains headers. Body/PDF search tokens disappear transactionally with eviction, while header/filename search remains. Retention removes only eligible local metadata and files; it never issues server DELETE/EXPUNGE. A separate local worker performs retention without network access. Pruning batches defer thread rebuilding. Shared-file references and in-flight downloads survive cleanup. Account removal cleans account payloads and cancels offline jobs.

## Correspondence filters and Room v9

The migration adds cache access/pin/failure metadata, folder scan checkpoints, body/attachment indexes, `contacts.corresponded`, canonical `message_senders`, and a generation-swapped local device-address cache. v8→v9 backfills actual senders and Sent To/Cc/Bcc plus queued-outbox recipients. Outbox recipient projections are bounded to avoid loading legacy multi-megabyte body columns. Malformed or oversized recipient data is skipped conservatively.

Both conversation and per-message `fromContacts` filters now require an exact canonical FROM address that is either corresponded or in the currently permitted device address book. Merely receiving spam or seeing an address does not establish correspondence. Empty addresses and spoofed display names cannot match everything. Sent/outbox recipients set a sticky marker; later incoming mail cannot reset it. Suggestions still include seen addresses independently.

Address-book reads are on device only, permission-gated, batched, and cached. Permission results, activity resume, and debounced provider changes refresh the cache; revocation clears its active generation. Paging observes the relevant tables and invalidates on changes. No contact information is uploaded or logged.

## Validation

Via `scripts/gw`: full `:mail:test`, `:data:testDebugUnitTest`, `:app:assembleDebug`, and R8 `:app:assembleRelease` passed on the 1651d33 rebase. The final 23f521a integration run has 426 mail cases and 171 data cases with zero failures (five and four opt-in/platform cases skipped respectively); debug assembly passed. The final R8 outcome is recorded in the PR.

Added coverage includes:

- GreenMail plus a counting TCP proxy: a message with a 2 MiB attachment transfers under 100 KiB in bodies-only mode; attachment fetch is separate and Seen is preserved. Backwards pages resume through expunged UID anchors. Size/cancellation failures clean staging files. Selected-part PGP cache preserves canonical signed bytes and renders text.
- Repository tests: concurrent quota reservations, LRU, pins, active readers, pending changes, snoozes, shared draft/outbox references, failed-connection cache survival, low device space/global cap, immediate FTS eviction, UTF-8-safe filenames and URI traversal refusal.
- Offline tests: scan beyond the initial window, pause/recreate/resume, no duplicate payload downloads, optional attachment mode with stable IDs, network/charging gates, and explicit pin while bulk is paused.
- More than 400 retention candidates across multiple batches; protected items survive. WorkManager request constraints are asserted.
- v8→v9 migration/backfill including a 3 MiB legacy outbox body; older migration chains are carried through v9.
- Both contact-filter modes: seen-only versus corresponded, Sent/outbox backfill, empty/spoofed sender rejection, permission grant/refresh/revoke and paging invalidation.

An opt-in **read-only** Infomaniak `LiveOfflinePartsTest` passed: selected body cache 524 ms, 13,353 HTML bytes, Seen unchanged. The test emits aggregate counts/timing only. No mailbox writes or phone operations were performed. Real-device storage-pressure, OS-deferred work and Settings interactions still need Claude's smoke test; this feature is not marked owner-verified Done.

## Next separate task

Close out IMAP hardening with evidence per owner subpoint. Normal FolderSync still rebuilds conversations per header and again after flag inventory; remove the remaining quadratic behavior in its own focused PR. Then prepare the off-by-default read-only JMAP design report before implementation.
