# FR-14 — proposed Room v11: JMAP identities and memberships

**Status: approved in principle by Claude; implementation deferred until after the first public release. Documentation only; no entities, migration, contract or runtime code changed.** Based on main `bd6cefa` (#79), exported Room schema 10 and the current DAO/query builders. This specifies the data design left open by [the engine design](codex-jmap-design.md). Version 11 names the reviewed sketch, not a reservation: implementation must use the next free schema version after the release and obtain the lead's go-ahead. The core messages-table rebuild is explicitly excluded from the current release cycle.

## Decision and invariants

Keep `messages.id`, `threads.id` and `folders.id` as the shared local identities. Add account-scoped JMAP identity maps and a common many-to-many membership table. Keep the existing IMAP folder/UID columns as an **optional pair**, exposed to wire code through a typed location. Existing IMAP physical copies remain separate rows; this migration does not deduplicate or re-thread them.

For JMAP, one remote Email has exactly one MessageRow, one cached body and one attachment set, irrespective of how many mailboxes contain it. Its `folderId` and `uid` are SQL NULL. Remote string IDs never become numeric UIDs, paths or local primary keys. Removing one membership does not delete the canonical message or its cache. A remote Thread maps to one local ThreadRow, with no subject fallback.

An existing account remains IMAP/SMTP. Selecting JMAP creates a new, explicitly enabled account; changing an existing account's protocol or selected remote account in place is unsupported. Authentication secrets remain in the existing encrypted `accounts.secret`, with unchanged vault/AAD handling for old accounts. Migration performs no network access, credential decryption, file deletion or queued-operation replay.

## Concrete schema delta

All remote identifiers use case-sensitive TEXT/BINARY comparison. Local IDs stay INTEGER/Long. `accountId` below always means Dot Mail's local account ID; `remoteAccountId` means the Session account ID. One explicitly selected remote account belongs to each local JMAP account in this slice. Selecting an additional shared account creates another local account rather than silently importing it.

### Existing tables

| Table | Proposed change |
|---|---|
| `accounts` | Add `protocol TEXT NOT NULL DEFAULT 'IMAP_SMTP'`; allowed runtime values `IMAP_SMTP` / `JMAP`. Add UNIQUE `(id,protocol)` for message/account protocol coherence. Existing `config`, `secret`, errors and IDs remain byte-for-byte unchanged. |
| `messages` | Add the same `protocol` discriminator/default. Change only `folderId` and `uid` to nullable INTEGER; retain every other v10 field, local ID and body column. Preserve UNIQUE `(folderId,uid)` and existing indexes. Add UNIQUE `(id,accountId)` for account-consistent child references. |
| `folders`, `threads` | Add UNIQUE `(id,accountId)` indexes, without rebuilding their contents. No server identifier is stored in their numeric primary keys. |

Message foreign keys become `(accountId,protocol) → accounts(id,protocol)`, `(threadId,accountId) → threads(id,accountId)`, and nullable `(folderId,accountId) → folders(id,accountId)`, all with DELETE CASCADE / UPDATE NO ACTION. The nullable folder FK retains v10 IMAP folder-deletion behavior; it cannot cascade-delete JMAP messages whose location is NULL.

Required location invariant:

- IMAP_SMTP: folderId and uid are both non-null. Preserve existing positive, zero and negative values during migration; only a positive UID is a server location. Negative values retain their existing pending-move meaning.
- JMAP: both columns are null.
- Half-null locations, unknown protocols and changing a populated account's protocol are rejected.

Room's entity annotations do not describe arbitrary CHECK constraints. Specify these guards as named SQLite validation triggers, installed by one shared database callback for fresh v11 databases and at migration completion. Do not rely on a migration-only CHECK that fresh Room databases lack. The implementation must test fresh/upgrade constraint parity. Protocol membership of `jmap_accounts` is also checked before insert/update.

### New tables

Unless otherwise specified, columns are NOT NULL; foreign keys cascade on deletion and reject identity updates. Remote-account identity is immutable for a configured local account. Unique local mapping IDs prohibit two remote objects claiming the same local object.

| Table | Columns and keys |
|---|---|
| `jmap_accounts` | `accountId INTEGER PRIMARY KEY` FK accounts; `remoteAccountId TEXT`; nullable `apiUrl TEXT`, `sessionState TEXT`, `mailboxState TEXT`, `emailState TEXT`; `generation INTEGER DEFAULT 0`; nullable `rescanGeneration INTEGER`. UNIQUE `(accountId,remoteAccountId)`. Session URL and explicitly approved origins live in account configuration; cached apiUrl never grants trust. |
| `jmap_mailboxes` | `accountId`, `remoteAccountId`, `remoteMailboxId TEXT`; `folderId INTEGER UNIQUE`; nullable `parentRemoteMailboxId TEXT`; `sortOrder INTEGER`; nullable `queryState TEXT`; `nextPosition INTEGER DEFAULT 0`, `queryGeneration INTEGER DEFAULT 0`, `windowComplete INTEGER DEFAULT 0`. PRIMARY KEY `(accountId,remoteAccountId,remoteMailboxId)`. Composite FKs to jmap_accounts and `(folderId,accountId)` to folders. |
| `jmap_threads` | `accountId`, `remoteAccountId`, `remoteThreadId TEXT`; `threadId INTEGER UNIQUE`. PRIMARY KEY `(accountId,remoteAccountId,remoteThreadId)`; composite FKs to jmap_accounts and `(threadId,accountId)` to threads. |
| `jmap_emails` | `accountId`, `remoteAccountId`, `remoteEmailId TEXT`; `messageId INTEGER UNIQUE`; `remoteThreadId TEXT`, `blobId TEXT`, `keywordsJson TEXT`; `remoteDeleted INTEGER DEFAULT 0`, `metadataGeneration INTEGER DEFAULT 0`. PRIMARY KEY `(accountId,remoteAccountId,remoteEmailId)`; composite FKs to jmap_accounts, jmap_threads' remote key, and `(messageId,accountId)` to messages. Index the remote-thread FK prefix through `remoteThreadId`. |
| `message_folders` | `accountId`, `messageId`, `folderId`; PRIMARY KEY `(messageId,folderId)`; composite FKs `(messageId,accountId) → messages(id,accountId)` and `(folderId,accountId) → folders(id,accountId)`. Covering index `(folderId,messageId,accountId)` for folder lists; index `(messageId,accountId)` for its composite FK. |

For each identity-map table, index its local composite FK as well as its unique local ID. The exported v11 schema must contain those indexes explicitly; do not ignore Room's missing-FK-index warnings. Account prefixes in the remote primary keys cover remote-account deletion and lookup. Add further indexes only when representative EXPLAIN QUERY PLAN results justify them.

`message_folders` is the authoritative read membership source for **both** protocols. To avoid two independent IMAP membership writers, SQLite triggers maintain its one IMAP membership on MessageRow insert/location update; message deletion cascades it. A move updates the raw IMAP location inside the existing operation transaction and its membership follows automatically. Guard membership inserts/updates against adding a second/wrong IMAP folder. JMAP writes its membership set explicitly in its metadata transaction; the IMAP mirror triggers do not run for JMAP rows. No migration-generated trigger copies bodies or parses header JSON.

FolderRow.path remains an IMAP path only for IMAP accounts. A JMAP folder needs a collision-free internal key to satisfy existing `(accountId,path)` uniqueness: `jmap:` plus separately base64url-encoded remote account/mailbox IDs, separated by a character outside that alphabet. Display/search names use FolderRow.name and the JMAP parent mapping, not this technical key. An unresolved parent is allowed while metadata loads; do not enforce a self-FK on the remote parent ID. IMAP cursor fields on JMAP folders retain inert defaults and are never consulted by wire code.

JMAP metadata is encoded into the existing common header JSON through a protocol-neutral encoder. Do not construct an IMAP Header with UID 0 to reach Codec.header. The transaction verifies that MessageRow.threadId agrees with the remote-thread map. Retain all remote keywords in keywordsJson while projecting `$seen`/`$flagged` to unread/starred. Body paths, AttachmentRow ownership and FTS rowids continue to use the canonical local message ID.

## Optional typed IMAP location and public contracts

Raw Room columns are nullable; the internal accessor returns `ImapLocation?`, after checking account/message protocol and both fields. It distinguishes `Server(folderId, validity, uid > 0)` from `PendingMove(targetFolderId, localUid < 0, operationId, sourceFolderId, sourceValidity, sourceUid)`. Resolve the latter using the existing queued operation, including legacy operations matched by folder/UID when messageLocalId is absent. A zero UID or missing pending operation produces a typed unavailable/pending error, never a wire UID or `!!` crash. JMAP returns no IMAP location.

Every wire entry point dispatches by account protocol before endpoint access: sync, search, body/attachment fetch, IDLE, folder operations, password verification and send. Current OperationRow, DraftMirrorRow and OfflineFolderRow remain IMAP-only, including their sentinel values and persisted retries. No existing operation is interpreted as a JMAP write. First JMAP integration is read-only: reject unsupported actions before optimistic local updates, and do not start IMAP/SMTP workers for it. A later JMAP write queue/submission design needs separate approval.

Public contract changes requiring coordination with Claude:

- Account exposes protocol/capabilities; typed connection configuration represents IMAP/SMTP versus JMAP without dummy SMTP hosts. Old JSON without a discriminator decodes as IMAP/SMTP. First-slice token authentication is explicit; it must not fall into the password/XOAUTH2 SMTP branch.
- Replace Message.folderId with `folderIds: Set<Long>`; an empty set is valid for protected local content whose remote email disappeared. IMAP projects a singleton. Update DemoMailGraph and callers together; no fabricated folder ID 0.
- SearchResult carries `matchedFolderIds: Set<Long>` and the UI retains the originating Mailbox. IndexedSearchRow's scalar folderId is no longer the canonical message location. SearchRoute/ThreadRoute must preserve source scope, including a SmartFolder, rather than infer it from the first membership.
- Keep the real-folder `dotmail.folderId` notification extra: choose an actual notifying membership and use it as action scope. It is navigation context, not a claim that the message has only one folder.

## v10 → v11 migration sketch

The v10 exported schema has seven FK children of `messages`: **attachments, body_text, search_matches, search_documents, search_pending, cache_access, message_senders**. Draft source IDs, snooze message IDs and operation messageLocalId are additional logical references without that FK. All must retain their IDs/values.

Changing NOT NULL location fields requires a table rebuild compatible with the project's older Android SQLite versions. Renaming the original parent first can retarget references; dropping a parent can cascade-delete child rows. Disabling foreign keys inside the transaction is not a remedy. See SQLite's [table-rebuild guidance](https://www.sqlite.org/lang_altertable.html#making_other_kinds_of_table_schema_changes) and [foreign-key DROP behavior](https://www.sqlite.org/foreignkeys.html#fk_schemacommands).

Use this explicit parent/child replacement plan with FK enforcement left on, within Room's upgrade transaction:

1. Verify the expected v10 schema/dependency inventory and account/thread/folder coherence before destructive DDL. Pre-existing inconsistent ownership must abort with a recoverable upgrade error for separate diagnosis, never be silently reassigned across accounts or deleted. Save sqlite_sequence high-water marks for every table that will be rebuilt, including deleted message/attachment IDs above the current maximum. Fail safely if an unaccounted-for dependent table exists.
2. Drop only the six custom `search_message_*` / `search_attachment_*` triggers, before any destructive DDL. Keep the FTS4 virtual table and its rowids intact.
3. Copy each of the seven children into an ordinary, transaction-local backup table in the same database, using explicit-column SQL `INSERT … SELECT`. These backups have no FKs/triggers. Do not use Kotlin cursors, TEMP memory tables or `SELECT *` materialization for body/attachment payloads.
4. Add accounts.protocol/default and the parent unique indexes. Create `messages_v11` with the intended v11 columns/FKs. Copy all v10 messages with explicit columns and protocol IMAP_SMTP, including text/html, headers, negative UIDs, flags and bodyPath unchanged.
5. Drop the seven original child tables, then the old messages table. No remaining child FK can cascade-delete preserved content; the custom FTS delete trigger is absent. Rename messages_v11 to messages and recreate its exact indexes. Never rename old messages to a backup name.
6. Recreate the seven child tables/indexes with their original definitions referencing the new `messages`; restore exact rows/IDs by SQL. Restore sqlite_sequence to at least each saved high-water mark and the greatest live ID. Other parent tables, drafts/outbox, snoozes, operations, v10 thread_repairs/repair_mutes and their preferences are untouched.
7. Create the five new tables/indexes. Backfill `message_folders(accountId,messageId,folderId)` from all existing messages. All JMAP identity/state tables start empty. Do not deduplicate, replay moves, rewrite config JSON or re-seed completed v10 thread repairs.
8. Install search triggers and the v11 location/protocol/IMAP-membership guards, using the same installers as fresh v11 creation. Do not enqueue a full text reindex merely because location columns changed: FTS/document content and IDs are unchanged.
9. Require foreign_key_check success, child/message row-count preservation and one exact matching membership per migrated IMAP row. Drop backup tables, then let Room validate the generated v11 schema before committing. Never enable destructive migration fallback or edit Room's identity hash manually.

Use the exported v10 DDL/explicit column inventory during implementation, not hand-inferred child schemas. Rollback must restore v10 on any failure. This approach temporarily duplicates database content and needs free disk for copies plus journal/WAL overhead; no fixed multiplier can guarantee space for all payloads. On SQLITE_FULL, preserve/rollback the database and report failure; never delete cached files to make an upgrade succeed. The upgrade must run before workers/IDLE start. No dependence on newer SQLite ALTER COLUMN, writable_schema, deferred CASCADE behavior or changing foreign_keys inside Room's transaction.

## Existing query and writer impact

| Current code/query family | Required v11 behavior |
|---|---|
| MailDatabase `byUid`, pendingMessage/unkeyedPending, folderInventory and UID/metadata repair queries | Remain explicitly IMAP-only over non-null location fields. Introduce separate projections with typed locations; a JMAP row cannot enter UIDNEXT/CONDSTORE, negative-UID or EXPUNGE paths. |
| RealMailGraph paging, MessageMode, ThreadQuery.smartFolder/smartMessages, category/unified predicates | Select membership with EXISTS over message_folders + folders. Preserve one canonical message result even if several memberships match; never multiply counts through a bare join. |
| RealMailGraph.actionRows/apply/move | Return `(message, matching source folder IDs)`, not only MessageRows. Same predicate selects display and action scope. Inbox-scoped archive removes only Inbox membership, preserving Sent and other labels. Unified role operates on each selected message's matching role memberships; flags remain canonical/global for selected JMAP emails. JMAP mutations stay disabled in the read-only slice. |
| FullContentSearch.sql and IndexedSearchRow | Filter by EXISTS membership. Put explicit folder scope, `folder:` name filter and role predicates in the same matching-membership test; do not match different folders independently. Rank narrow canonical IDs once; fetch matched memberships separately for emitted results. Technical JMAP paths are not user folder-name matches. Default global search requires visible membership too; retained no-membership content is available only through an explicit saved-offline scope. |
| page/pageMessages/searchPage/observeThread | Observe MessageFolderRow changes as well as messages/folders; membership-only edits must invalidate lists and reader folderIds. Obtain reader rows + memberships consistently in a Room transaction/relation projection. No N+1 body reads. |
| rebuildThread, thread(), summaries, notification routing | JMAP counts canonical email IDs, not membership joins or RFC Message-ID coincidences; server thread identity is authoritative. Preserve existing IMAP duplicate-copy behavior. Normal list aggregates exclude remotely removed JMAP records with no memberships. Notification scope is a real selected membership. |
| storagePathBatch/protectedMessage/outsideLatest/outsideAge and StorageBodies | Replace single-folder Drafts checks with EXISTS Drafts membership. Replace positive-UID retention eligibility with protocol-specific remote eligibility (positive IMAP UID or live JMAP identity); keep negative-UID protection IMAP-only. Never drop JMAP rows via INNER JOIN on nullable m.folderId. Retain draft/outbox/source/snooze/offline-save/pending-op protections. Count and evict canonical content once; acquiring body/attachment sources dispatches by protocol before resolving an IMAP location. |
| OfflineDownloads/OfflineFolderRow and progress totals | Keep the backwards UID scan IMAP-only. Read-only JMAP setup must not schedule that worker. Later JMAP offline plans count canonical emails, not sum(totalEmails) across overlapping folders; progress cannot promise implemented downloads prematurely. |
| AttachmentBrowser.attachmentsPaging | Use EXISTS an eligible membership instead of `m.folderId NOT IN(TRASH,SPAM)`. An Inbox+Trash-labelled email is eligible through Inbox; trash-only/spam-only/no-membership records are hidden by default. Saved offline content has an explicit separate scope. |
| FolderOps deletion/rename/reconciliation | IMAP deletion retains its cascades and pending-op handling. JMAP mailbox deletion removes its folder/map/memberships only, never all canonical emails that also belong elsewhere. Display hierarchy uses remote parent mapping. |
| DraftMirroring, Sync.deliverOutbox, CalendarReplies, Composer account choice | Gate capabilities before work or local mutation. Preserve legacy SMTP/APPEND/uncertain-send semantics for IMAP; no SMTP fallback or APPEND for JMAP. Local draft saving/removal remains possible, but remote draft mirroring/reply/send controls are unsupported until reviewed. |
| ThreadRepair, correspondence, snippet prefetch | Historical repair runs only for IMAP targets/locations. IMAP remote snippets keep UID guards; cached snippet helpers can be protocol-neutral. Mark correspondence once when a canonical JMAP email has Sent membership, not once per label. Address-book permission semantics are unchanged. |

Representative proposed folder selection (bound values, schematic SQL):

```sql
SELECT m.id, m.threadId, m.subject, m.snippet
FROM messages m
WHERE EXISTS (
  SELECT 1 FROM message_folders mf
  JOIN folders f ON f.id=mf.folderId AND f.accountId=mf.accountId
  WHERE mf.messageId=m.id AND mf.accountId=m.accountId
    AND mf.folderId IN (:sourceFolderIds)
    AND f.name LIKE :folderName ESCAPE '!'
)
ORDER BY m.sentAt DESC,m.id DESC;
```

The existing FTS rowid, body_text chunks, search documents, attachment IDs, cache pins, ConversationIds virtual IDs and local notification/thread IDs remain stable. Membership-only changes invalidate scoped search pages, but do not require rebuilding unchanged text. Generic body metadata queries must continue using MESSAGE_META with NULL text/html projections; migration or paging must not regress the CursorWindow fix.

## Cursor commit and deletion rules

A per-JMAP-account coordinator serializes metadata publication, without holding the global IMAP syncLock or blocking body/cache reads. Every commit checks the selected remote account and generation; late results from removed/reconfigured accounts cannot repopulate them. Session, mailbox, email and per-folder query states remain separate opaque strings. NULL means not initialized; the empty string is a valid initialized state.

Bootstrap with Email/get(ids=[]) to capture a baseline before paged initial metadata fetches; then drain changes from that baseline. A metadata get's newer state must not skip unapplied account changes. Store each fully reconciled delta and its new email cursor atomically. Use `upsertIds` and destroyed-last precedence from the engine; list overlaps are legal. An incomplete/malformed get never advances the cursor. On cannotCalculateChanges, invalidate the cursor, refresh already-cached IDs (including older pinned mail) plus the bounded initial window, and catch up from a new baseline. Query-window absence never means global deletion.

Explicit remote destruction/notFound removes memberships. A protected cached message retains its local ID/files and jmap_emails row with remoteDeleted=1; normal mailbox/category queries exclude it. An explicit saved-offline view can expose retained content with no folder IDs and no remote actions. Unprotected rows can be removed with normal cache cleanup. Deleting only a mailbox never sets the email tombstone. Full row removal clears its identity mapping; ordinary membership removal does not. Rollback after process death leaves both cursor and memberships at the preceding committed batch.

## Approval and implementation gates

Approved direction, retained for post-release implementation: five new tables; common membership reads with automatic IMAP mirror triggers; nullable location pair + protocol/coherence guards; canonical JMAP rows and explicit public folder-set contracts; and the seven-child rebuild migration. No JMAP operation queue, EventSource state table or submission schema is included in v11.

After the release and renewed implementation approval, acceptance must include:

- File-backed v10→v11 and older-version-chain fixtures: a >3 MiB legacy body, attachment paths, FTS hits/highlights, deleted-ID sequence high-water marks, source drafts, uncertain outbox rows, queued moves/flags/folder ops, snooze/mute, and pending/completed v10 repairs. Exact IDs, payloads and counts survive reopen; fresh/upgrade schema and guard behavior match.
- Failure injection before/after child backup, parent replacement and restore; disk-full/rollback behavior; no cached-file deletion; foreign_key_check and index/query-plan checks. No FK-disabling workaround.
- Same remote IDs in different accounts, conflicting mappings, partial gets, empty opaque state, overlapping changes, membership-only invalidation, multi-label paging/FTS/attachment eligibility without duplication, and Inbox actions preserving Sent.
- Pinned remote-deleted content, canonical disk accounting, IMAP pending-move body fetch after migration, and every JMAP attempt to enter an IMAP/SMTP/IDLE/draft/offline worker rejected before local mutation.
- Full scripts/gw mail/data/app checks, R8 release and lead-owned device regression after integration. This document itself has only static source/schema review; it implements or tests no migration.
