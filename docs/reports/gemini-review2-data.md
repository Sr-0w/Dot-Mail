# Dot Mail — `:data` & `:mail` Engine Audit (Review 2)

**Reviewer:** Gemini (Design & Quality)  
**Scope:** `data/src/main/kotlin/**` and `mail/src/main/kotlin/**` — sync correctness, concurrency, Room transactions, memory (50k messages / 25 MiB bodies), battery/sockets, and security.  
**Summary:** **17 findings — 7 P0 · 6 P1 · 4 P2**

---

## Executive Summary

The `:data` and `:mail` modules have a clean, readable structure and thoughtful safety invariants (CAS outbox state transitions, header-injection checks in `Mime.build`, XXE-hardened autoconfig XML parsing, Keystore AES-GCM with per-account AAD). However, tracing real-world mailbox behavior exposed **7 P0 issues** that will break on real accounts:
1. Unconditional subject-key matching merges all unrelated emails sharing common subjects (`"Invoice"`, `"Receipt"`, `"Verification code"`) into a single thread.
2. IMAP IDLE times out every 30 seconds (`mail.imap.timeout = 30_000`) and tears down all IDLE sockets after every sync because `Sync.kt` updates the `accounts` table (`dao.syncError`), triggering `observeAccounts().collectLatest`.
3. A single global `syncLock` `Mutex` is held across multi-minute network syncs while local optimistic UI actions (`apply`, `move`), `loadBody`, and `retrySend` wait on the same lock.
4. Storing 25 MiB `text`/`html` columns directly in `MessageRow` + calling `SELECT * FROM messages` (`folderMessages` / `threadMessages`) crashes SQLite's 2 MB `CursorWindow`, and `Sync.kt` rebuilds every thread in the folder on every sync.
5. 25 MiB body downloads and outbox sends allocate 4–5 in-memory copies (~100–150 MiB heap peak) and permanently leak `.eml` and attachment files on disk.
6. Multi-folder thread actions move `Sent` replies out of `Sent`, leave orphaned `uid < 0` rows that permanently brick threads, and show duplicate messages on Gmail (`INBOX` + `[Gmail]/All Mail`).
7. `HtmlSanitizer` flags every `<style>` tag as a blocked remote image, while `HtmlMessageView`'s `<meta name="color-scheme" content="dark light">` disables WebView algorithmic darkening in dark mode.

---

## P0 — Broken / Crash / Data Corruption / Severe Battery or Memory Failure

### P0-1 · `Threading.keys` unconditionally indexes `"subject:<normalized>"` for all messages, merging unrelated conversations with common subjects
- **Where:**
  - `mail/src/main/kotlin/app/dot/mail/engine/Threading.kt:12-18`
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:165-168`
- **What's wrong:**
  - `Threading.keys(header)` (`Threading.kt:12-18`) returns:
    ```kotlin
    fun keys(header: Header): List<String> = buildList {
        header.gmailThreadId?.takeIf { it > 0 }?.let { add("gmail:$it") }
        header.references.firstOrNull()?.let { add("id:$it") }
        header.inReplyTo?.let { add("id:$it") }
        header.messageId?.let { add("id:$it") }
        normalizeSubject(header.subject).takeIf { it.isNotEmpty() }?.let { add("subject:$it") }
    }
    ```
  - And `RealMailGraph.upsertHeader` (`RealMailGraph.kt:166-167`) resolves `threadId` via:
    ```kotlin
    val threadId = keys.firstNotNullOfOrNull { dao.threadKey(folder.accountId, it) }
        ?: dao.insertThread(ThreadRow(accountId = folder.accountId, subject = header.subject))
    keys.forEach { dao.insertKey(ThreadKeyRow(folder.accountId, it, threadId)) }
    ```
  - When a brand-new, top-level email arrives (not a reply: `inReplyTo == null && references.isEmpty()`, with a fresh `Message-ID: <msg2@shopB.com>`), its own `id:<msg2@shopB.com>` is not yet in `thread_keys`. `firstNotNullOfOrNull` therefore **always falls through to `"subject:<normalized>"`**!
  - Because the first email with that subject already registered `"subject:<normalized>"` in `thread_keys`, **every unrelated email in the user's mailbox with a common subject (`"Invoice"`, `"Receipt"`, `"Your verification code"`, `"Security alert"`, `"Order confirmation"`, `"Hello"`) is permanently merged into one giant thread** — even when every message has a valid RFC 5322 `Message-ID` and even on Gmail (`X-GM-EXT-1`) if a later message shares the subject!
  - Conversely, `Threading.kt:14` only adds `header.references.firstOrNull()`, so if an intermediate message in a thread is synced before the root message (common when only the last 30 days are synced) and a later reply's `References` list was trimmed by an MUA, intermediate reference IDs are not indexed in `thread_keys`.
- **Concrete fix:**
  1. Index **all** `header.references` (`header.references.forEach { add("id:$it") }`), not just `firstOrNull()`.
  2. When `header.gmailThreadId != null`, use **only** `gmail:<id>` (and `id:` keys) — never merge by subject on servers that provide native thread IDs.
  3. On standard IMAP servers, only fall back to `"subject:<normalized>"` when the message is actually a reply/forward (`hasReplyPrefix(header.subject)` or `header.inReplyTo != null` or `header.references.isNotEmpty()`) or lacks a `Message-ID`:
  ```kotlin
  // Threading.kt:12-18
  private val REPLY_PREFIX = Regex("(?i)^(?:re|fw|fwd|aw|sv)(?:\\[\\d+])?:")

  fun keys(header: Header): List<String> = buildList {
      header.gmailThreadId?.takeIf { it > 0 }?.let {
          add("gmail:$it")
          header.messageId?.let { id -> add("id:$id") }
          return@buildList
      }
      header.references.forEach { add("id:$it") }
      header.inReplyTo?.let { add("id:$it") }
      header.messageId?.let { add("id:$it") }
      val isReplyOrNoId = header.messageId == null || header.inReplyTo != null ||
          header.references.isNotEmpty() || REPLY_PREFIX.containsMatchIn(header.subject.trim())
      if (isReplyOrNoId) {
          normalizeSubject(header.subject).takeIf { it.isNotEmpty() }?.let { add("subject:$it") }
      }
  }
  ```
  *(Or store `"subject:$norm"` on root messages so future `Re: <subject>` replies without `In-Reply-To` can find them, but in `upsertHeader` only **query** `"subject:$norm"` when `isReplyOrNoId` is true!)*

---

### P0-2 · IMAP IDLE times out every 30 seconds (`mail.imap.timeout = 30_000`) and restarts all IDLE sockets after every `syncAll()`
- **Where:**
  - `mail/src/main/kotlin/app/dot/mail/engine/MailClient.kt:24,159-170`
  - `data/src/main/kotlin/app/dot/mail/data/WorkScheduling.kt:50-67`
  - `data/src/main/kotlin/app/dot/mail/data/Sync.kt:81-82`
- **What's wrong:**
  Three bugs combine to turn `ForegroundIdle` into a continuous reconnect loop:
  1. **30-second socket read timeout kills IDLE (`MailClient.kt:24,160,168`):**
     `MailClient.idle` creates its store via `connect(endpoint, credentials)` → `session(...)`, which sets `mail.imap.timeout = 30000` (30 seconds `SO_TIMEOUT`). In Eclipse Angus, `IMAPFolder.idle()` blocks on socket read waiting for untagged server responses. When no email arrives in `INBOX` for 30 seconds, the socket throws `SocketTimeoutException` (wrapped in `MessagingException`), `idle()` exits and closes the connection, `ForegroundIdle` waits `delay(30_000)` (`WorkScheduling.kt:62`), and opens a fresh TLS + IMAP login connection every 60 seconds instead of idling for up to 24–29 minutes per RFC 2177.
  2. **`dao.syncError` at the end of every sync cancels and restarts all IDLE sockets (`Sync.kt:81`, `WorkScheduling.kt:54`):**
     `ForegroundIdle` observes `graph.dao.observeAccounts().collectLatest { accounts -> ... }`. At the end of every `syncAll()`, `Sync.kt:81` executes `dao.syncError(account.id, ...)` (`UPDATE accounts SET syncError=:error WHERE id=:id`). Room invalidates the `accounts` table, `observeAccounts()` emits a new list, and **`collectLatest` cancels the active `coroutineScope` — tearing down and reconnecting the IDLE sockets of every account after every single sync**! Worse, when `changed()` fires on IDLE (`WorkScheduling.kt:60`), it calls `graph.requestSync()`, which runs `syncAll()`, which calls `dao.syncError(...)`, which kills and reconnects the very IDLE socket that just reported the change!
  3. **Servers without `IDLE` capability reconnect every 30 seconds (`MailClient.kt:165`):**
     If `!s.hasCapability("IDLE")`, `MailClient.idle` returns `Unit` immediately, and `ForegroundIdle`'s `while (isActive)` loop (`WorkScheduling.kt:56-63`) reconnects and logs in every 30 seconds forever.
- **Concrete fix:**
  1. In `MailClient.idle`, use a dedicated session with an IDLE read timeout of `25 * 60 * 1000` (25 minutes, per RFC 2177's <29 min recommendation) and suspend until cancelled if `!s.hasCapability("IDLE")`:
     ```kotlin
     // MailClient.kt:165
     if (!s.hasCapability("IDLE")) {
         runCatching { s.close() }
         awaitCancellation()
     }
     ```
  2. In `WorkScheduling.kt:54`, only restart IDLE coroutines when the set of account IDs or credentials/endpoints changes:
     ```kotlin
     graph.dao.observeAccounts()
         .map { rows -> rows.map { Triple(it.id, it.config, it.secret) } }
         .distinctUntilChanged()
         .collectLatest { accounts -> ... }
     ```
  3. In `Sync.kt:81`, only call `dao.syncError(account.id, newError)` when `account.syncError != newError`.

---

### P0-3 · Global `syncLock` `Mutex` blocks optimistic UI writes (`apply`, `move`), `loadBody`, and `retrySend` behind background network sync
- **Where:**
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:46,125,143,179,198,243`
  - `data/src/main/kotlin/app/dot/mail/data/Sync.kt:18,88`
- **What's wrong:**
  - `RealMailGraph` uses a single `Mutex` (`syncLock`) for **both** long-running multi-folder network operations (`syncAll`, `serverSearch`, `deliverOutbox`) **and** user-interactive methods (`apply`, `move`, `loadBody`, `retrySend`).
  - `syncAll()` holds `syncLock` continuously while iterating over every account and every folder over the network (including up to 200 snippet fetches per folder). On a real mailbox or slow mobile connection, `syncAll()` holds `syncLock` for **30 to 120+ seconds**.
  - During that entire window:
    1. **Local optimistic writes freeze (`RealMailGraph.kt:179,198`):** `apply()` (Archive, Trash, Star, Mark read) and `move()` suspend waiting for `syncLock`, directly violating `Repositories.kt:22` (*"Writes apply to the local DB immediately (optimistic)"*) and freezing UI actions (such as `ThreadScreen` waiting for `vm.act(ARCHIVE)` before closing).
    2. **Opening an uncached email blocks (`RealMailGraph.kt:143`):** `loadBody(messageId)` cannot start downloading the message the user is looking at until all background folders of all accounts finish syncing.
    3. **Outbox retry fails (`RealMailGraph.kt:243`):** `retrySend` calls `if (!syncLock.tryLock()) return@withContext false`, rejecting user retries whenever background folder sync or search is running.
- **Concrete fix:**
  1. **Never hold `syncLock` in `apply()`, `move()`, or `loadBody()`:** Room's `database.withTransaction` already serializes local SQLite writes safely and atomically! `apply()` and `move()` only mutate Room (`messages`, `operations`, `threads`) and then call `requestSync()` — they do zero network I/O and do not need `syncLock`.
  2. `loadBody(messageId)` opens its own independent IMAP connection (`MailClient.body`) and writes inside `database.withTransaction`; use a per-message lock (or no `syncLock`) so reading an email is never blocked by background folder sync.
  3. Separate `outboxLock` from `syncLock` so `deliverOutbox` and `retrySend` never contend with IMAP folder sync.

---

### P0-4 · Storing 25 MiB `text`/`html` in `MessageRow` + `SELECT * FROM messages` crashes SQLite `CursorWindow` (2 MB limit) and causes $O(N^2)$ sync queries
- **Where:**
  - `data/src/main/kotlin/app/dot/mail/data/local/MailDatabase.kt:17,43-48`
  - `data/src/main/kotlin/app/dot/mail/data/Sync.kt:54,65,69`
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:159,174`
- **What's wrong:**
  1. **`SQLiteBlobTooBigException` crash on bodies > ~1.8 MB:**
     `MessageRow` (`MailDatabase.kt:17`) stores `val text: String?` and `val html: String?` inline in the `messages` table (`MailClient.body` allows messages up to 25 MiB). Android's SQLite `CursorWindow` is strictly capped at **2 MB**. Once any message in a folder has a downloaded `html` + `text` body exceeding ~1.8 MB (common for newsletters or HTML emails with embedded `data:image/...` URIs), `dao.folderMessages(folder.id)` (`SELECT * FROM messages WHERE folderId=:id`), `dao.threadMessages(id)`, and `dao.observeThread(id)` crash with `android.database.sqlite.SQLiteBlobTooBigException: Row too big to fit into CursorWindow` — permanently breaking sync for that folder and crashing `ThreadScreen`!
  2. **Rebuilding every thread in the folder on every sync (`Sync.kt:54,65`):**
     ```kotlin
     val old = dao.folderMessages(folder.id)
     ...
     (old.map { it.threadId } + dao.folderMessages(folder.id).map { it.threadId }).distinct().forEach { rebuildThread(it) }
     ```
     `old` contains **every message in the folder**, so line 65 calls `rebuildThread(it)` for **100% of threads in the folder on every sync**, even when 0 headers arrived and 0 flags changed!
  3. **$O(N^2)$ `dao.folderMessages(folder.id)` inside `upsertHeader` (`RealMailGraph.kt:159`):**
     For every new header with a `messageId`, `upsertHeader` calls `dao.folderMessages(folder.id).firstOrNull { it.uid < 0 && ... }`. During an initial 500-message sync, this executes `SELECT * FROM messages WHERE folderId=:id` **500 times** inside the loop (reading ~125,000 rows) and calls `rebuildThread(threadId)` **500 times** inside `upsertHeader` (`line 170`) before `Sync.kt:65` calls `rebuildThread` on all of them again!
  4. **Unconditional `dao.updateMessage` on unchanged flags (`Sync.kt:62-64`):**
     On non-CONDSTORE servers, `result.flags` contains every message in the folder, and line 63 calls `dao.updateMessage(row.copy(unread = !flag.seen, starred = flag.starred))` even when `row.unread == !flag.seen && row.starred == flag.starred`, triggering Room `InvalidationTracker` and forcing Paging3 to reload on every folder sync.
- **Concrete fix:**
  1. Move `text` and `html` out of `messages` into a separate `message_bodies(messageId PRIMARY KEY, text, html)` table (or files on disk for bodies > 256 KB), or ensure `folderMessages` / `threadMessages` use a lightweight projection (`MessageSummaryRow`) without `text` and `html`.
  2. Replace `dao.folderMessages(folder.id).firstOrNull { it.uid < 0 ... }` in `upsertHeader` with a targeted indexed query:
     ```kotlin
     @Query("SELECT * FROM messages WHERE folderId=:folder AND uid < 0")
     suspend fun pendingMoves(folder: Long): List<MessageRow>
     ```
  3. Track a `val touchedThreads = mutableSetOf<Long>()` in `Sync.kt:54-65` (adding only threads whose messages were actually inserted, deleted, or had a flag value change: `if (row.unread != !flag.seen || row.starred != flag.starred)`), and call `rebuildThread` **once per touched thread** at the end of the transaction (removing the per-message `rebuildThread` call inside `upsertHeader`).

---

### P0-5 · 25 MiB body downloads & outbox sends allocate 100–150 MiB on heap (`OutOfMemoryError`) and leak `.eml` and attachment files on disk
- **Where:**
  - `mail/src/main/kotlin/app/dot/mail/engine/MailClient.kt:103-110`
  - `mail/src/main/kotlin/app/dot/mail/engine/Mime.kt:75-107`
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:147-153,277-304`
  - `data/src/main/kotlin/app/dot/mail/data/Sync.kt:97-108,125,132-135`
- **What's wrong:**
  1. **4–5× in-memory multiplication on `loadBody` (`MailClient.kt:105-110`, `Mime.kt:78-91`, `RealMailGraph.kt:148-151`):**
     - `message.writeTo(output)` buffers up to 25 MiB in `ByteArrayOutputStream`.
     - `output.toByteArray()` allocates a second 25 MiB `ByteArray` while `output` is still strongly referenced in scope.
     - `Mime.parse` decodes each attachment into another `ByteArrayOutputStream` → `toByteArray()` and retains all `MimeAttachment.bytes: ByteArray` in `ParsedBody`.
     - `RealMailGraph.loadBody` (`line 151`) then parses `body.html` **three separate times with Jsoup** (`Jsoup.parse(it).text()`, `HtmlSanitizer.sanitize(it)`, and `Snippets.fromHtml(it)`).
     - Peak heap usage for a single 20–25 MiB email exceeds **100–150 MiB**, causing `OutOfMemoryError` on standard Android heaps.
  2. **Same heap spike on `deliverOutbox` (`Sync.kt:101,104,132`):**
     - Line 101 reads all attachment streams into `ByteArray`s in memory (`it.readBytes()`).
     - Line 132 re-parses the entire persisted `.eml` file (~34 MiB with base64 overhead) back into an in-memory `MimeMessage` just to call `mime.removeHeader("Bcc")` before `client.append` (note: `MimeMessage.writeTo` already omits `Bcc` headers by default, or `Bcc` is only an SMTP envelope `RCPT TO` and shouldn't be written into the `.eml` headers in the first place!).
  3. **Permanent disk storage leaks in `filesDir/outbox/` and `filesDir/attachments/`:**
     - When `deliverOutbox` succeeds (`"SENT"` at `Sync.kt:125` or `135`), **`row.mimePath` (`filesDir/outbox/<accountId>/<id>.eml`, up to 34 MiB per sent email!) is never deleted from disk** (`deleteOutbox` at `RealMailGraph.kt:266` deletes it on manual discard, but successful sends and `cancelSend` at `RealMailGraph.kt:236` never delete it!).
     - When messages are expunged/deleted during sync (`Sync.kt:55,56,63`) or `DELETE_FOREVER` (`RealMailGraph.kt:192`), `dao.deleteMessage(id)` cascades in SQLite **but never deletes the downloaded attachment files in `filesDir/attachments/<accountId>/`**.
     - When a draft is discarded (`discardDraft`) or sent (`send`), or an attachment is removed from a draft, the files copied by `cacheAttachments` (`RealMailGraph.kt:295`) are never deleted from `filesDir/attachments/<accountId>/`.
- **Concrete fix:**
  1. In `Sync.kt:125,135` (and `RealMailGraph.cancelSend`), delete the `.eml` file once delivery completes or is cancelled:
     ```kotlin
     row.mimePath?.let { File(it).delete() }
     ```
  2. In `Mime.build` (`Mime.kt:42`), do **not** write `Bcc` into the persisted RFC822 headers (pass `bcc` recipients separately to `transport.sendMessage(message, allRecipients)`), eliminating the need to re-parse the `.eml` file into a `MimeMessage` in `Sync.kt:132`.
  3. Stream raw message downloads in `MailClient.body` to a temporary file (`File.createTempFile`) with `SharedFileInputStream` instead of a 25 MiB `ByteArrayOutputStream`.

---

### P0-6 · Optimistic move (`uid = -message.id`) & multi-folder thread bugs: moving a thread moves `Sent` replies, creates duplicate messages on Gmail, and permanently bricks threads if `uid < 0` is orphaned
- **Where:**
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:135-138,158-197`
  - `data/src/main/kotlin/app/dot/mail/data/Sync.kt:35-66`
- **What's wrong:**
  1. **`apply(ARCHIVE / TRASH / SPAM)` moves messages out of `Sent` and re-moves messages already in the target folder (`RealMailGraph.kt:182-193`):**
     `dao.threadMessages(thread)` returns **all** messages in the thread across all folders (including the user's own replies in `SENT` and messages already in `ARCHIVE`). When the user archives a conversation from `INBOX`, `apply()` queues an IMAP `MOVE` from `Sent` → `Archive` for their sent replies, and queues an IMAP `MOVE` from `Archive` → `Archive` (`folder.id == target.id`) while setting `uid = -message.id`!
  2. **Gmail (`[Gmail]/All Mail`) duplicate messages in `ThreadScreen` and orphaned `uid < 0` rows (`RealMailGraph.kt:135,159,186`):**
     - On Gmail, every message in `INBOX` is also synced in `[Gmail]/All Mail` (`Role.ALL_MAIL`). Both rows share the same `threadId` and `Message-ID`.
     - While `rebuildThread` (`line 175`) deduplicates by `Message-ID`, **`RealMailGraph.thread(threadId)` (`line 135`) does NOT deduplicate by `Message-ID` (nor exclude `TRASH`/`SPAM`)**, so opening any conversation on a Gmail account renders every message twice!
     - Furthermore, archiving a thread on Gmail moves the `INBOX` row to `All Mail` (`uid = -id1`) AND moves the existing `All Mail` row to `All Mail` (`uid = -id2`). When `All Mail` syncs, `upsertHeader` (`line 159`) only updates `.firstOrNull { it.uid < 0 ... }` (`-id1`), leaving `-id2` at `uid < 0` forever!
  3. **Orphaned `uid < 0` rows are never cleaned up and permanently brick the thread (`Sync.kt:36-38,55-56`, `RealMailGraph.kt:183`):**
     - `Sync.kt:55-56` only deletes expunged/reset rows where `it.uid > 0`.
     - If a moved message (`uid = -message.id`) had no `Message-ID` header (`header.messageId == null` in `upsertHeader`), or was deleted on the server by another client before `Archive` synced, or its `OperationRow` failed due to `UIDVALIDITY` change (`Sync.kt:36`), the row remains at `uid < 0` forever. Any subsequent attempt to open or act on that thread throws `IllegalArgumentException("Message move is awaiting server synchronization")` (`RealMailGraph.kt:183`).
- **Concrete fix:**
  1. In `RealMailGraph.apply` (`line 182`), skip moving messages that are already in `target.id` (`if (target != null && message.folderId == target.id) continue`), and when `action == MessageAction.ARCHIVE`, only move messages currently in `INBOX` (never move messages out of `SENT` or `TRASH`).
  2. In `RealMailGraph.thread(threadId)` (`line 135`), deduplicate rows by `Codec.field(row.header, "messageId") ?: "local:${row.id}"` (matching `rebuildThread`).
  3. In `Sync.kt:55-56`, also clean up any `old` row with `it.uid < 0` when no pending `MOVE` operation targeting `folder.path` still exists for that account and the message did not appear in `result.headers`.

---

### P0-7 · `HtmlSanitizer` flags every `<style>` tag as a blocked remote image, and `HtmlMessageView`'s `color-scheme` meta tag disables WebView dark mode
- **Where:**
  - `mail/src/main/kotlin/app/dot/mail/engine/HtmlSanitizer.kt:20-21`
  - `app/src/main/kotlin/app/dot/mail/ui/thread/HtmlMessageView.kt:70-72,129`
- **What's wrong:**
  1. **False-positive `"remote image blocked"` banner on almost all HTML emails (`HtmlSanitizer.kt:21`):**
     ```kotlin
     val style = el.attr("style")
     if (Regex("(?i)url\\s*\\(|@import|\\\\").containsMatchIn(style) || el.tagName() == "style") { remote = true; blocked++ }
     ```
     Because of `|| el.tagName() == "style"`, **any HTML email containing a `<style>` tag** (even `<style>body { font-family: sans-serif; }</style>` with zero external URLs) sets `remote = true` and increments `blocked++`! In `ThreadScreen.kt:415`, this causes the `"1 remote image blocked to protect your privacy [Load]"` banner to appear on virtually every HTML email, and clicking `"Load"` does nothing because `<style>` is still stripped by `Safelist`.
  2. **Dark mode broken on HTML emails with inline text colors (`HtmlMessageView.kt:71,129`):**
     `HtmlMessageView.kt:129` injects `<meta name="color-scheme" content="dark light">` when `dark == true`. Per AndroidX WebKit / Chromium Blink specifications, `WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)` is **skipped** whenever a document declares `color-scheme: dark`. Because `HtmlSanitizer` strips `<style>` blocks (removing `@media (prefers-color-scheme: dark)`) while preserving inline `style="color:#000000"`, Chromium disables auto-darkening and renders `#000000` black text directly on Dot Mail's `#0D0D0E` near-black background.
- **Concrete fix:**
  ```kotlin
  // HtmlSanitizer.kt:20-21 — only count <style> if its CSS text actually references url()/@import
  val style = el.attr("style")
  val cssContent = if (el.tagName() == "style") el.data() else style
  if (Regex("(?i)url\\s*\\(|@import|\\\\").containsMatchIn(cssContent)) { remote = true; blocked++ }
  ```
  ```kotlin
  // HtmlMessageView.kt:129 — declare "light" when algorithmic darkening is supported so Blink auto-darkens inline styles, or strip dark inline foreground colors without backgrounds
  <meta name="color-scheme" content="light">
  ```

---

## P1 — Sync Scalability, Search Correctness, Security & Protocol Issues

### P1-1 · 50k-message mailbox scalability & `UIDNEXT` bugs in `MailClient.sync`
- **Where:** `mail/src/main/kotlin/app/dot/mail/engine/MailClient.kt:55-77`
- **What's wrong:**
  1. **Unconditional `1:LASTUID` UID inventory on every sync (`lines 62-64`):**
     On every sync of every folder, `f.getMessagesByUID(1, UIDFolder.LASTUID)` + `f.fetch(inventory, UID)` fetches all UIDs from `1` to `*`. On a 50,000-message archive or `All Mail` folder, this allocates 50,000 `IMAPMessage` instances and a 50,000-entry `Set<Long>` per folder every 15 minutes — even though Dot Mail only caches the last 30 days (≤ 500 messages) locally!
  2. **Fetching `FLAGS` for all 50,000 messages on non-CONDSTORE servers and on `reset = true` (`line 71`):**
     `val flagsMessages = if (!reset && cursor.highestModSeq >= 0 && store.hasCapability("CONDSTORE")) ... else inventory`
     When `reset == true` or the server lacks `CONDSTORE`, `f.fetch(inventory, FLAGS)` fetches flags for all 50,000 messages in the folder instead of only the locally cached UID range (`minCachedUid..LASTUID`).
  3. **Incremental sync fetches the *oldest* 500 new messages instead of the newest (`line 67`), and breaks if `f.uidNext == -1L` (`lines 60,75`):**
     - If 1,200 messages arrived while the device was offline, `f.getMessagesByUID(max(1, cursor.uidNext), UIDFolder.LASTUID).filterNotNull().take(limit)` fetches the oldest 500 first, leaving today's newest 700 messages unsynced.
     - If a server does not report `UIDNEXT` (`f.uidNext == -1L`), `checkpoint` becomes `-1L` on `reset`, and `next > cursor.uidNext` (`-1 > -1`) is permanently `false` on future syncs.
- **Concrete fix:**
  Pass `minCachedUid: Long` in `SyncCursor` (the minimum positive `uid` stored in Room for that folder) so `inventory` and `flagsMessages` only query `getMessagesByUID(minCachedUid, UIDFolder.LASTUID)` rather than `1..LASTUID`, and when `f.uidNext <= 0`, compute `next = (existing.maxOrNull() ?: 0L) + 1L`.

---

### P1-2 · Operation replay opens 1 TCP/TLS connection per message and syncs twice; `SnippetFetcher` sends 200 sequential `UID FETCH` commands and aborts the whole batch on 1 bad message
- **Where:**
  - `data/src/main/kotlin/app/dot/mail/data/Sync.kt:32-52,69-78`
  - `mail/src/main/kotlin/app/dot/mail/engine/MailClient.kt:112-136`
  - `mail/src/main/kotlin/app/dot/mail/engine/SnippetFetcher.kt:24-47`
- **What's wrong:**
  1. **Double sync + unbatched per-message connections (`Sync.kt:33-51`):**
     `syncAll` calls `client.sync` (Connection #1), then loops `for (op in dao.operations...)` opening a **brand-new IMAP connection per single `OperationRow`** (`client.move` / `client.setFlags`), and then calls `client.sync` a **second time** (`if (replayed)`). Archiving 15 threads with 2 messages each opens **32 separate TCP/TLS IMAP connections** in a tight loop, tripping per-IP/user connection limits on Dovecot/Gmail/Infomaniak.
  2. **200 sequential round-trips + all-or-nothing batch failure in `MailClient.snippets` (`MailClient.kt:118`, `Sync.kt:71`):**
     `MailClient.snippets` calls `SnippetFetcher.fetch` sequentially inside `messages.associate { ... }`, issuing up to 200 individual `UID FETCH <uid> (BODY.PEEK[...])` commands per folder. Worse, `Sync.kt:71` wraps the entire 200-UID call in a single `try/catch`: if 1 message out of 200 has a malformed MIME header or fails, `emptyMap()` is returned, `snippetFetched` remains `false` for all 200 messages, and all 200 messages are re-fetched on every sync forever.
- **Concrete fix:**
  1. Replay queued operations **first** (grouped by `(action, target)` so `client.move` and `client.setFlags` pass `uids: LongArray` in a single IMAP connection after checking `folder.uidValidity == validity`), and then call `client.sync` **once**.
  2. In `MailClient.snippets` (`MailClient.kt:118`), wrap `SnippetFetcher.fetch` in `runCatching { ... }.getOrDefault("")` per message so one malformed message is marked `snippetFetched = true` with `""` instead of poisoning the whole 200-message batch.

---

### P1-3 · Server search (`serverSearch`) drops all body-matched messages locally and opens connections to every folder of every account
- **Where:**
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:100-134`
  - `mail/src/main/kotlin/app/dot/mail/engine/MailSearch.kt:18`
- **What's wrong:**
  - In `MailSearch.serverTerm()` (`MailSearch.kt:18`), free-text terms search the server using `OrTerm(SubjectTerm(v), OrTerm(FromStringTerm(v), BodyTerm(v)))`.
  - When the IMAP server returns messages that matched via **`BodyTerm(v)`** (the search word is in the message body, not the subject or sender), `serverSearch` (`RealMailGraph.kt:131`) calls `upsertHeader(folder, it)`, which inserts the `MessageRow` with `text = null` and `snippet = ""`.
  - Immediately afterward, `searchSource` (`RealMailGraph.kt:118`) queries Room with:
    `(m.senderSearch LIKE ? OR m.subject LIKE ? OR m.snippet LIKE ? OR m.text LIKE ?)`
  - Because `text` is `null` and `snippet` is `""`, **every message that matched on the server by body text fails the local SQL `LIKE` filter and never appears in the search results**!
  - Additionally, `serverSearch` (`line 129`) iterates over **every folder** of every account (including `TRASH`, `SPAM`, and all custom folders), opening a separate IMAP connection per folder while holding `syncLock`.
- **Concrete fix:**
  Fetch snippets (or store matched server UIDs / search tokens) for headers returned by `client.search`, and scope `serverSearch` to primary folders (`INBOX`, `ARCHIVE`, `ALL_MAIL`, `SENT`) rather than every folder in the account.

---

### P1-4 · Autodiscovery security & protocol flaws: `AutoconfigXml` accepts `SocketType.PLAIN` (cleartext password auto-verify) and `pop3`, and `MxResolver` is a no-op stub
- **Where:**
  - `mail/src/main/kotlin/app/dot/mail/engine/autoconfig/AutoconfigXml.kt:41-59,89`
  - `mail/src/main/kotlin/app/dot/mail/engine/autoconfig/AutoDiscovery.kt:74`
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:72-76`
  - `app/src/main/kotlin/app/dot/mail/ui/account/AccountSetup.kt:159-189`
- **What's wrong:**
  1. **Silent cleartext password transmission via autoconfig (`AutoconfigXml.kt:44`, `RealMailGraph.kt:74`, `AccountSetup.kt:183`):**
     `AutoconfigXml` accepts `<socketType>plain</socketType>` (`SocketType.PLAIN` → `Security.NONE`). In `AddAccountViewModel.connect()`, when `repo.discover(email)` returns a config in `Step.Credentials`, the app immediately calls `repo.verify(config, s.password)` **before** showing `Step.Manual`. If a provider autoconfig XML returns `SocketType.PLAIN`, `MailClient.verify` connects with `TransportSecurity.NONE` and sends the user's password in **cleartext** over the network without the user ever seeing the `Security.NONE` warning!
  2. **`AutoconfigXml` returns POP3 servers as `imap` (`AutoconfigXml.kt:50`):**
     `val imapEl = pick(root, "incomingServer", mapOf("imap" to 3, "pop3" to 1))` accepts `type="pop3"` and returns it in `ParsedServers.imap` even though Dot Mail only supports IMAP. Worse, because `score = typeScore + socketRank`, a `pop3` + `SSL` block (`1 + 3 = 4`) ties with `imap` + `PLAIN` (`3 + 1 = 4`) and wins if listed first!
  3. **MX discovery (`Source.MX`) is dead code in production (`AutoDiscovery.kt:74`, `RealMailGraph.kt:73`):**
     `AutoDiscovery`'s default parameter is `private val mxResolver: MxResolver = MxResolver { emptyList() }`, and `RealMailGraph.discover` calls `AutoDiscovery()` with no `MxResolver`. Custom domains hosted on Infomaniak, Google Workspace, Fastmail, Proton, OVH, or Gandi never use `BuiltInProviders.lookupByMx` in production.
- **Concrete fix:**
  1. In `AddAccountViewModel.connect()` (`AccountSetup.kt:170`), if `found.imap.security == Security.NONE || found.smtp.security == Security.NONE`, drop to `Step.Manual` with an error/warning instead of auto-calling `repo.verify`.
  2. In `AutoconfigXml.kt:50`, remove `"pop3" to 1` (`mapOf("imap" to 3)`) so POP3-only XML falls back to ISPDB/guess, and multiply `typeScore * 10` so `type` always outranks `socketRank`.
  3. Provide an Android `DnsResolver` (API 29+ `DnsResolver.getInstance()` or mini DNS MX query) implementation of `MxResolver` in `:data`.

---

### P1-5 · MIME parser fragility on malformed headers and missing `Content-Disposition: inline` on CID images
- **Where:**
  - `mail/src/main/kotlin/app/dot/mail/engine/Mime.kt:94-103`
  - `mail/src/main/kotlin/app/dot/mail/engine/MailClient.kt:95-102`
  - `mail/src/main/kotlin/app/dot/mail/engine/SnippetFetcher.kt:18,44`
- **What's wrong:**
  1. **Uncaught `ParseException` on non-strict MIME headers (`Mime.kt:97-98`, `MailClient.kt:97`, `SnippetFetcher.kt:18`):**
     In Jakarta Mail, calling `p.disposition`, `p.fileName`, or `p.isMimeType(...)` on a message with unquoted special characters in `Content-Disposition` or `Content-Type` (extremely common in real-world mail) throws `jakarta.mail.internet.ParseException`, aborting `sync()` or `loadBody()`.
  2. **CID inline images without `Content-Disposition: inline` treated as regular attachments (`Mime.kt:98`):**
     `Mime.kt:98` sets `inline = p.disposition.equals(Part.INLINE, true)`. Many mail clients (Outlook, Apple Mail) emit `multipart/related` parts with `Content-ID: <...>` and **no** `Content-Disposition` header (`p.disposition == null`). Setting `inline = false` marks the message as `hasAttachments = true` in the inbox list and attaches inline signature icons when forwarding (`RealMailGraph.kt:226`).
- **Concrete fix:**
  ```kotlin
  // Mime.kt:97-98 — guard header parsing and treat Content-ID parts (not explicitly attachment) as inline
  val disp = runCatching { p.disposition }.getOrNull()
  val rawName = runCatching { p.fileName }.getOrNull()
  val cid = runCatching { p.getHeader("Content-ID")?.firstOrNull()?.trim('<', '>') }.getOrNull()
  val isInline = disp.equals(Part.INLINE, true) || (cid != null && !disp.equals(Part.ATTACHMENT, true))
  ```

---

### P1-6 · `WorkScheduling.sync` uses `ExistingWorkPolicy.APPEND_OR_REPLACE`, chaining redundant back-to-back full syncs
- **Where:** `data/src/main/kotlin/app/dot/mail/data/WorkScheduling.kt:18-21`
- **What's wrong:**
  - `WorkManager.getInstance(context).enqueueUniqueWork("dotmail.sync", ExistingWorkPolicy.APPEND_OR_REPLACE, ...)`
  - In WorkManager, when `"dotmail.sync"` is currently `RUNNING`, `APPEND_OR_REPLACE` **appends** a new `SyncWorker` to the end of the active work chain every time `WorkScheduling.sync` is called (after the 300ms debounce in `requestSync()`).
  - If the user performs 5 actions (or IDLE emits 5 events) over 10 seconds while a slow sync is running, WorkManager chains 5 sequential `SyncWorker`s that will run back-to-back — and if any worker in the chain returns `Result.retry()`, the appended children are blocked behind exponential backoff.
- **Concrete fix:**
  Use `ExistingWorkPolicy.KEEP` in `WorkScheduling.sync(context)` combined with an in-memory `AtomicBoolean` (`resyncNeeded`) checked at the end of `syncAll()` so at most one follow-up pass runs if new requests arrived during an active sync.

---

## P2 — Database Indexing, Cleanup & Edge-Case Polish

### P2-1 · Missing SQLite indices on `threads(lastAt, id)`, `messages(sentAt)`, and `outbox(state, dueAt)`, plus N+1 attachment queries in `thread()`
- **Where:**
  - `data/src/main/kotlin/app/dot/mail/data/local/MailDatabase.kt:12-27`
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:94,135-138`
- **What's wrong:**
  1. `RealMailGraph.paging` (`line 94`) queries `SELECT t.* FROM threads t WHERE t.count>0 AND (...) ORDER BY t.lastAt DESC, t.id DESC`, but `ThreadRow` (`MailDatabase.kt:12`) only indexes `accountId` — forcing a full table scan and temporary B-tree sort on `threads`.
  2. `RealMailGraph.thread(threadId)` (`line 137`) runs `dao.attachments(row.id)` inside `rows.map { ... }`, executing $N$ separate queries per emission instead of a single `@Query("SELECT * FROM attachments WHERE messageId IN (:ids)")`.
- **Concrete fix:**
  Add `Index(value = ["lastAt", "id"])` on `ThreadRow`, `Index(value = ["threadId", "sentAt"])` on `MessageRow`, and batch-load attachments in `RealMailGraph.thread(threadId)`.

---

### P2-2 · Stale data & notification cleanup: deleted server folders, empty `ThreadRow`s, read/archived notifications, and removed account channels/workers
- **Where:**
  - `data/src/main/kotlin/app/dot/mail/data/Sync.kt:25-30`
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:90,172-197`
  - `data/src/main/kotlin/app/dot/mail/data/MailNotifications.kt:19-48`
- **What's wrong:**
  1. **Deleted remote folders (`Sync.kt:25-30`):** `syncAll` inserts new folders from `remoteFolders` but never deletes local `FolderRow`s whose `path` no longer exists on the server.
  2. **Empty `ThreadRow`s (`RealMailGraph.kt:172-178`):** When `distinct.isEmpty()`, `rebuildThread` leaves the `ThreadRow` (with `count = 0`) and its `thread_keys` in SQLite forever instead of deleting the `ThreadRow` (which cascades to `thread_keys`).
  3. **Stale notifications (`RealMailGraph.kt:179`, `MailNotifications.kt:29`):** Marking a thread read, archiving it, or trashing it inside the app never calls `NotificationManager.cancel(threadId.toString(), 1)`.
  4. **Account removal (`RealMailGraph.kt:90`):** `removeAccount` does not delete `"dotmail.account.$accountId"` from `NotificationManager` or cancel pending `"dotmail.outbox.<id>"` WorkManager jobs for that account.
- **Concrete fix:**
  - In `rebuildThread`, if `distinct.isEmpty()`, call `dao.deleteThread(id)`.
  - In `RealMailGraph.apply`, cancel notification `thread.toString(), 1` when `action != MessageAction.MARK_UNREAD && action != MessageAction.STAR && action != MessageAction.UNSTAR`.

---

### P2-3 · `deliverOutbox` checks `config.imap.host` instead of `config.smtp.host` for Gmail auto-Sent behavior
- **Where:** `data/src/main/kotlin/app/dot/mail/data/Sync.kt:124`
- **What's wrong:**
  - `val autoSent = config.imap.host.equals("imap.gmail.com", true) || config.imap.host.equals("imap.googlemail.com", true)`
  - It is Gmail's **SMTP** server (`smtp.gmail.com` / `smtp.googlemail.com`) that automatically saves a copy to Sent during `SMTP` submission, and `MailClient.append` (`MailClient.kt:142-143`) already has an idempotency guard (`opened.search(HeaderTerm("Message-ID", id)).isEmpty()`). Checking `smtp.host` (or relying on `MailClient.append`'s `HeaderTerm("Message-ID", id)` check) is more accurate.

---

### P2-4 · `KeystoreCredentialVault` key invalidation surfaces as generic `"Could not synchronize account"` instead of `isAuthError = true`
- **Where:**
  - `data/src/main/kotlin/app/dot/mail/data/CredentialVault.kt:31-37`
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:48-51`
  - `data/src/main/kotlin/app/dot/mail/data/Sync.kt:82`
- **What's wrong:**
  - `Sync.kt:82` only classifies `jakarta.mail.AuthenticationFailedException` as `"Authentication failed"`, and `RealMailGraph.kt:50` constructs `SyncState.Error(it)` without ever setting `isAuthError = true` (`Models.kt:31`). If Keystore decryption fails (`GeneralSecurityException` / `KeyPermanentlyInvalidatedException`) or `AuthenticationFailedException` occurs, `isAuthError` is never set to `true`.
- **Concrete fix:**
  In `RealMailGraph.kt:50`, set `SyncState.Error(it, isAuthError = it == "Authentication failed")`, and catch `GeneralSecurityException` in `credentials()` as an auth error requiring password re-entry.
