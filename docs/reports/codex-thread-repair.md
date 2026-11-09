# Historical conversation repair (FR-04 follow-up)

The forward threading/scope fixes shipped in #67. This separate change repairs conversation groups created by the old subject fallback. It does not move messages on the mail server.

## Upgrade and recovery

Room v10 adds `thread_repairs` and `repair_mutes`. Migration 9→10 queues the existing thread IDs once. Fresh databases and newly created conversations have no repair targets. Completed targets disappear in the same transaction as their repairs. If a normal sync merges a pending target into another thread, the target follows the surviving thread rather than being lost through a foreign-key cascade.

A network-constrained WorkManager job runs after upgrade/startup. Ordinary identified single-message groups are completed with atomic SQL, without fetching headers. Multi-message groups use bounded pages of 100 metadata rows. The worker holds one authenticated IMAP lease per account; recovery fetches envelope/header metadata and BODYSTRUCTURE, never MIME bodies, and does not set Seen. Authentication failures surface through the existing account error state; a successful sync schedules unfinished repairs again after reauthentication.

Older `Codec.header` JSON omitted **In-Reply-To and Gmail X-GM-THRID**. Their absence cannot safely mean “unrelated.” This change records both, with `threadingRevision=1`, on all newly stored headers. Legacy groups first recover these fields through UID/UIDVALIDITY-checked IMAP reads. Recovery preserves local flags, bodies, previews, classification, attachment references and operation IDs. Concurrent local header edits invalidate recovery instead of being overwritten. Strict recovery distinguishes an expunged UID from a header parse failure; transient/parse failures retry.

## Splitting and state

Within each original group, connected components follow current authoritative rules: Message-ID/References/In-Reply-To, Gmail thread IDs where available, and duplicate physical copies with the same Message-ID. Subject fallback applies only to messages with no authoritative identifiers and matching participants within the existing 30-day window. A changed reply subject does not break its reference chain; an independent message with a normal Message-ID cannot join through its subject.

The component containing the oldest message by `(sentAt, local message ID)` retains the original thread ID. Others receive fresh IDs. No message IDs change. The repair rebuilds summaries and keys, and removes the old subject aliases from repaired groups. Search/attachment/draft-source/queued-operation references continue to resolve through their unchanged message IDs.

Thread-level snoozes are copied with their original deadline, creation time and resurfaced time. Message-level snoozes follow their own message only. A surfaced snooze therefore cannot produce a second reminder merely because of repair. Scheduling is restored both after repair and at process startup, closing the commit-to-WorkManager crash gap.

Muted state is copied through the Room journal in the split transaction. Both the repository mute flow and notification eligibility consult that journal before preferences are flushed. The flush commits preferences synchronously once per journal snapshot before deleting rows in bounded batches (avoiding quadratic preference rewrites for large splits). A crash at either boundary safely repeats the operation; an explicit unmute clears the journal under the same mutex. Valid later thread merges also transfer pending mute intent. The original thread's notification identity and mute state remain intact.

Planning runs outside the global sync mutex and outside a database transaction. A streamed SHA-256 fingerprint is rechecked inside the final write transaction before applying the plan. Header/membership changes cause a retry; flag/body-only changes survive. Body columns never enter repair cursors. Write parameter batches are capped at 100. The working set contains member IDs, timestamps and threading keys, proportional to the group rather than its body size; there is no arbitrary group-size skip. Each group commits atomically, so an exceptionally large historically corrupted group can still hold SQLite's writer while its split is applied.

## Actual undecidable cases

If an old message's omitted threading fields cannot be recovered because its UID/folder disappeared, UIDVALIDITY changed, or its identity no longer matches, the **whole original group remains unchanged**, marked `SOURCE_UNAVAILABLE`. A malformed stored header is similarly retained as `MALFORMED_HEADER`. Losing those fields makes a valid In-Reply-To-only or Gmail relationship indistinguishable from a historical subject-only merge. Guessing would damage valid conversations. No network outage is treated as permanent evidence loss.

Only multi-message groups requiring this missing evidence are affected. For support, the reason codes can be inspected with `SELECT state,COUNT(*) FROM thread_repairs GROUP BY state`; they contain no mail content. They are not silently marked repaired.

## Verification

`ThreadRepairTest` covers a real file-backed v9 fixture containing faulty merges, migration to v10, retained oldest ID, a recovered In-Reply-To-only relationship, preference persistence and reopening/idempotence. Other cases cover reference chains/orphan parents, changed subjects, physical copies, Gmail precedence, legacy participant/time boundaries, 240 independent messages, a 3 MiB legacy body, attachment and queued-operation preservation, message-level snoozes, surfaced snoozes, singleton network avoidance, concurrent metadata edits, transient recovery failure and unavailable sources. Fault injection covers transaction rollback, death after split commit but before mute flush, and death after preference commit but before journal deletion. A GreenMail test recovers the omitted header on the real engine path (including a queued optimistic move with a temporary negative UID) and checks bodies remain unloaded and all messages remain unseen.

Validation: `scripts/gw :mail:test :data:testDebugUnitTest :app:assembleDebug` passed (2m54); mail 432 tests / 5 skipped, data 221 / 4 skipped, no failures; all 12 repair tests executed. The subsequent full data run after the summary recalculation fix passed (2m27). The first release/R8 build with the explicit worker keep rule passed (3m46). The subsequent combined full-data/release run after journal optimization was interrupted by an SSH connection reset, so final R8 completion is not claimed. The focused 240-split/240-mute test crossing the journal read boundary then passed (26s), compiling the latest sources.

Handoff: Claude reassigned thread repair/Room v10 to t14 while this implementation was in progress. This branch is a backup for t14 to inspect/reuse, not a competing PR. Rebase on current main/new toolchain and rerun the full validation before publishing. FR-14 JMAP is now t3/Sol’s active task. No new dependencies, public repository contract changes, real-account writes, phone access or blur/glass changes.
