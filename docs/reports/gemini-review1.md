# Dot Mail — Whole-Codebase Design & Quality Review (Review 1)

**Reviewer:** Gemini (Design & Quality)  
**Scope:** `:designsystem`, `:app`, and `:data` contract/integration points against `PROJECT_BRIEF.md`, `docs/TEAM.md`, and `docs/ARCHITECTURE.md`.  
**Summary:** **23 findings — 5 P0 · 13 P1 · 5 P2**

---

## Executive Summary

The architecture, module boundaries (`:mail`, `:data`, `:designsystem`, `:app`), and overall visual restraint of the **Quiet UI** aesthetic are well-conceived. However, static analysis of the UI, design system, and data-contract integration surfaced **5 P0 defects** (uncaught ViewModel exceptions on the Main thread, unreachable local drafts, auto-collapsing unread messages on thread open, lost uncommitted recipients on Send/Back, and synchronous Main-thread ContentResolver I/O during composition), **13 P1 issues** (WCAG AA contrast failures for `textTertiary` in both themes, sub-48dp touch targets across `DotIconButton` and interactive rows, fixed heights clipping text under font scaling, broken TalkBack semantics, background `BiometricPrompt` firing in `onStop()`, and silent sync states/errors), and **5 P2 polish items**.

---

## P0 — Broken / Crash / Data Loss / Main-Thread Blocking

### P0-1 · Uncaught `IllegalArgumentException` crashes app on Main thread during message actions and thread open
- **Where:**
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxViewModel.kt:109-127`
  - `app/src/main/kotlin/app/dot/mail/ui/thread/ThreadScreen.kt:150-167`
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:183-203`
- **What's wrong:**
  - `RealMailGraph.apply` and `RealMailGraph.move` throw `IllegalArgumentException` via `require(...)` in three realistic user scenarios:
    1. **Acting on or opening a thread with a pending move (`RealMailGraph.kt:183`):** Moving/archiving/trashing a message sets `uid = -message.id` locally until background sync replays the operation (`RealMailGraph.kt:192,202`). If the user opens that thread (which immediately triggers `ThreadViewModel.onOpened()` → `apply(MARK_READ)`) or toggles star/read on it before WorkManager finishes syncing, `require(message.uid > 0)` throws `IllegalArgumentException("Message move is awaiting server synchronization")`.
    2. **Server missing a role folder (`RealMailGraph.kt:187`):** If an IMAP server lacks an `Archive`, `Spam`, or `Trash` folder, `require(targetRole == null || target != null)` throws `IllegalArgumentException`.
    3. **Cross-account move from Unified Inbox (`RealMailGraph.kt:201`):** In `"All inboxes"`, selecting a thread from Account B while Account A is focused in the drawer and picking a folder in the `"Move to"` sheet calls `move` with Account A's folder, throwing `IllegalArgumentException("Moving between accounts is unsupported")`.
  - Neither `InboxViewModel.act`, `InboxViewModel.moveSelection`, `ThreadViewModel.onOpened`, nor `ThreadViewModel.act` wraps `apply`/`move` in `runCatching`, crashing the process with an uncaught exception in `viewModelScope`.
- **Concrete fix:**
  ```kotlin
  // InboxViewModel.kt:109-127
  fun act(action: MessageAction, ids: Collection<Long> = _selection.value) {
      if (ids.isEmpty()) return
      val count = ids.size
      viewModelScope.launch {
          runCatching { graph.mail.apply(action, ids.toList()) }
              .onSuccess {
                  _selection.value = emptySet()
                  confirmation(action, count)?.let { _events.send(it) }
              }
              .onFailure { _events.send(it.message ?: "Action could not be completed") }
      }
  }

  fun moveSelection(folderId: Long) {
      val ids = _selection.value.toList()
      if (ids.isEmpty()) return
      viewModelScope.launch {
          runCatching { graph.mail.move(ids, folderId) }
              .onSuccess {
                  _selection.value = emptySet()
                  _events.send(if (ids.size == 1) "Moved" else "Moved ${ids.size} conversations")
              }
              .onFailure { _events.send(it.message ?: "Couldn't move conversation") }
      }
  }
  ```
  ```kotlin
  // ThreadScreen.kt:150-167 (ThreadViewModel)
  fun onOpened() {
      if (markedRead) return
      markedRead = true
      viewModelScope.launch {
          runCatching { graph.mail.apply(MessageAction.MARK_READ, listOf(threadId)) }
      }
  }

  fun act(action: MessageAction) {
      viewModelScope.launch {
          runCatching { graph.mail.apply(action, listOf(threadId)) }
              .onSuccess {
                  when (action) {
                      MessageAction.ARCHIVE, MessageAction.TRASH, MessageAction.SPAM,
                      MessageAction.DELETE_FOREVER, MessageAction.MARK_UNREAD -> _events.send(ThreadEvent.Close)
                      else -> Unit
                  }
              }
              .onFailure { _events.send(ThreadEvent.Message(it.message ?: "Action failed")) }
      }
  }
  ```
  *(Also in `RealMailGraph.kt:183`, `MARK_READ`/`MARK_UNREAD`/`STAR`/`UNSTAR` should update local flags on rows with `uid < 0` without queuing an invalid negative-UID server operation rather than throwing.)*

---

### P0-2 · Saved local drafts never appear in the Drafts folder, and `onOpenDraft` passes a `threadId` instead of a `draftId`
- **Where:**
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxScreen.kt:220`
  - `app/src/main/kotlin/app/dot/mail/ui/Navigation.kt:110`
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:96-99,209-217`
  - `app/src/main/kotlin/app/dot/mail/demo/DemoMailGraph.kt:72-82,145-151`
- **What's wrong:**
  - `ComposeViewModel` saves drafts via `ComposeRepository.saveDraft(draft)` into the Room `drafts` table (`DraftRow`, `RealMailGraph.kt:214`) or `DemoMailGraph.drafts` map (`DemoMailGraph.kt:147`).
  - However, opening the **Drafts** folder in the UI (`FolderRole.DRAFTS`) queries `MailRepository.threads(mailbox)`, which queries `SELECT t.* FROM threads t WHERE t.count>0 AND EXISTS(SELECT 1 FROM messages m ...)` (`RealMailGraph.kt:94-99`).
  - Consequently:
    1. **Local drafts saved in `ComposeScreen` never appear in the Drafts folder** (in both production and Demo mode). Once the user closes `ComposeScreen` (`"Draft saved"`), there is no way anywhere in the UI to find or reopen the draft.
    2. Conversely, if a server-synced message exists in the IMAP Drafts folder, tapping it in `InboxScreen.kt:220` calls `onOpenDraft(t.threadId)` → `ComposeRoute(draftId = t.threadId)` → `graph.compose.loadDraft(route.draftId)`, passing a `ThreadRow.id` into a lookup on `DraftRow.id`.
- **Concrete fix:**
  - Either surface local `DraftRow` items in `MailRepository.threads` when `role == FolderRole.DRAFTS` (or expose `val drafts: Flow<List<Draft>>` on `ComposeRepository` and render them in `InboxScreen` when `header.role == FolderRole.DRAFTS` using `draft.localId`), and ensure `discardDraft`/`send` removes the corresponding entry.

---

### P0-3 · Opening a multi-message thread immediately collapses all unread messages except the last one
- **Where:** `app/src/main/kotlin/app/dot/mail/ui/thread/ThreadScreen.kt:132-133,150-154,227,281-282`
- **What's wrong:**
  - In `ThreadScreen.kt:281`:
    ```kotlin
    val expanded = toggled[m.id] ?: (m.isUnread || m == last || list.size == 1)
    ```
    And on line 227:
    ```kotlin
    LaunchedEffect(messages != null) { if (messages != null) vm.onOpened() }
    ```
  - When a thread with multiple unread messages opens (e.g., `m1` read, `m2` unread, `m3` unread), on the first emission `m2` is expanded because `m2.isUnread == true` and `toggled[m2.id] == null`.
  - Immediately on that same frame, `vm.onOpened()` calls `graph.mail.apply(MessageAction.MARK_READ, listOf(threadId))`, which updates Room (`unread = false` on all messages in the thread).
  - Milliseconds later, `vm.messages` emits the updated list with `m2.isUnread == false`. Because `toggled[m2.id]` is still `null` and `m2 != last`, **`m2` immediately collapses while the user is looking at it** (and before its body even finishes loading).
- **Concrete fix:**
  Latch the initial expanded message IDs in `ThreadViewModel` before marking the thread as read:
  ```kotlin
  // ThreadScreen.kt (ThreadViewModel)
  fun onOpened(initialMessages: List<Message>) {
      if (markedRead) return
      markedRead = true
      _toggled.update { current ->
          val defaults = initialMessages.associate { m ->
              m.id to (m.isUnread || m == initialMessages.lastOrNull() || initialMessages.size == 1)
          }
          defaults + current
      }
      viewModelScope.launch {
          runCatching { graph.mail.apply(MessageAction.MARK_READ, listOf(threadId)) }
      }
  }
  ```
  And in `ThreadScreen.kt:227`:
  ```kotlin
  LaunchedEffect(list != null) { if (list != null) vm.onOpened(list) }
  ```

---

### P0-4 · Uncommitted recipient text in `RecipientRow` leaves Send disabled or is silently lost on Send / Back
- **Where:** `app/src/main/kotlin/app/dot/mail/ui/compose/ComposeScreen.kt:133,257-305` and `app/src/main/kotlin/app/dot/mail/ui/compose/ComposeViewModel.kt:41,175-209`
- **What's wrong:**
  - `RecipientRow` holds typed text in local composable state: `var input by rememberSaveable(field) { mutableStateOf("") }` (`ComposeScreen.kt:267`). An address is only committed to `ComposeViewModel` when the user types `,`, `;`, ` ` (after `@`), presses IME Next, or moves focus to another text field (`onFocusChanged`).
  - If a user types `alice@example.com` in `To` and taps the `SendButton`:
    1. If `draft.to` is empty, `state.hasRecipients` is `false` (`ComposeScreen.kt:133`), so **`SendButton` is disabled (`enabled = false`) even though a valid address is typed in `To`**.
    2. If `draft.to` already has a chip (e.g. Reply All, or adding a second recipient in `To`/`Cc`/`Bcc`) and the user types another address without pressing comma/Next and taps **Send** or **Back**, `SendButton` (`Box(...clickable)`) does **not** clear focus from `BasicTextField`, so `onFocusChanged` never runs — **the typed recipient is silently discarded and never sent or saved to the draft**.
- **Concrete fix:**
  Hoist pending recipient inputs into `ComposeViewModel` (e.g., `pendingInput: Map<RecipientField, String>`) and commit any non-blank pending inputs inside `send()` and `close()` before validating/saving:
  ```kotlin
  // ComposeViewModel.kt
  fun updateInput(field: RecipientField, value: String) { ... }
  fun commitPendingInputs(): Boolean =
      RecipientField.entries.all { field ->
          val raw = _state.value.pendingInputs[field].orEmpty().trim()
          raw.isEmpty() || addRecipient(field, raw)
      }
  ```

---

### P0-5 · Synchronous `ContentResolver.query` on the Main thread inside `remember` during composition
- **Where:** `app/src/main/kotlin/app/dot/mail/ui/compose/ComposeScreen.kt:348,366-374`
- **What's wrong:**
  - Inside `AttachmentChips`, line 348 runs:
    ```kotlin
    val (name, size) = remember(u) { describe(context, Uri.parse(u)) }
    ```
  - `describe()` executes `context.contentResolver.query(...)` **synchronously on the Main thread during composition**. When picking files from SAF / cloud `DocumentsProvider`s (Nextcloud, Google Drive, MTP), `query()` performs IPC and network/disk metadata lookup that can block the UI thread for hundreds of milliseconds or trigger an ANR.
- **Concrete fix:**
  Load attachment metadata asynchronously on `Dispatchers.IO` via `produceState`:
  ```kotlin
  // ComposeScreen.kt:348
  val parsed = remember(u) { Uri.parse(u) }
  val (name, size) by androidx.compose.runtime.produceState(
      initialValue = (parsed.lastPathSegment ?: "Attachment") to null as Long?,
      key1 = parsed,
  ) {
      value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          describe(context, parsed)
      }
  }
  ```

---

## P1 — Clearly Wrong UX / Brief Violations / Accessibility / Compose Correctness

### P1-1 · `textTertiary` fails WCAG AA (4.5:1) contrast in both Dark and Light themes
- **Where:** `designsystem/src/main/kotlin/app/dot/design/Tokens.kt:58,78`
- **What's wrong:**
  - `textTertiary` is used across the app for essential `11sp–14sp` text: message list timestamps (`InboxScreen.kt:467`), message snippets (`InboxScreen.kt:487`, `ThreadScreen.kt:389`), `"Reply to …"` prompt (`ThreadScreen.kt:585`), `"To / Cc / Bcc"` field labels (`ComposeScreen.kt:250,273`), account email addresses (`MailDrawer.kt:150`), `"Remove"` account action (`SettingsScreen.kt:95`), and `SectionLabel` (`Basics.kt:199`).
  - Exact WCAG 2.1 contrast ratios computed from `Tokens.kt`:

| Theme | Token (`textTertiary`) | Surface Token | Contrast Ratio | WCAG AA (4.5:1 text / 3.0:1 large) |
|---|---|---|---:|---|
| **Dark** | `#6C6C73` ($L = 0.1470$) | `background` `#0D0D0E` ($L = 0.0040$) | **3.73 : 1** | ❌ Fails AA normal text |
| **Dark** | `#6C6C73` ($L = 0.1470$) | `surface` `#161618` ($L = 0.0076$) | **3.47 : 1** | ❌ Fails AA normal text |
| **Dark** | `#6C6C73` ($L = 0.1470$) | `surfaceRaised` `#202023` ($L = 0.0145$) | **3.12 : 1** | ❌ Fails AA normal text |
| **Light** | `#8E8E96` ($L = 0.2705$) | `background` `#FFFFFF` ($L = 1.0000$) | **3.25 : 1** | ❌ Fails AA normal text |
| **Light** | `#8E8E96` ($L = 0.2705$) | `surface` `#F6F6F7` ($L = 0.9197$) | **3.01 : 1** | ❌ Fails AA normal text |
| **Light** | `#8E8E96` ($L = 0.2705$) | `surfaceRaised` `#EDEDEF` ($L = 0.8469$) | **2.78 : 1** | ❌ Fails even 3.0:1 minimum |

- **Concrete fix:**
  Adjust `textTertiary` in `Tokens.kt` so it meets ≥ 4.5:1 on both `background` and `surface` while preserving the three-tier visual hierarchy below `textSecondary` (`#A0A0A7` = 7.48:1 dark; `#5E5E66` = 6.42:1 light):
  ```kotlin
  // Tokens.kt:58 (DarkColors) — 4.96:1 on background, 4.62:1 on surface
  textTertiary = Color(0xFF808089),

  // Tokens.kt:78 (LightColors) — 5.05:1 on background, 4.68:1 on surface
  textTertiary = Color(0xFF6E6E76),
  ```

---

### P1-2 · Widespread touch targets below the 48×48dp Android accessibility minimum
- **Where:**
  - `designsystem/src/main/kotlin/app/dot/design/components/Basics.kt:99,139` (`DotButton(compact = true)` = `36dp` height; `DotIconButton` = `44×44dp`)
  - `designsystem/src/main/kotlin/app/dot/design/components/Inputs.kt:157` (`SearchField` = `46dp` min height)
  - `app/src/main/kotlin/app/dot/mail/ui/compose/ComposeScreen.kt:157-159` (`"Cc Bcc"` = `34dp` tall), `231-241` (`SendButton` = `40×40dp`), `327-340` (`RecipientChip` = `26dp` tall)
  - `app/src/main/kotlin/app/dot/mail/ui/thread/ThreadScreen.kt:383-387` (`"to <recipients>"` details toggle = **`16dp` tall**), `508-511` (`PlainTextBody` quote toggle `...` = `28×44dp`)
  - `app/src/main/kotlin/app/dot/mail/ui/account/AccountSetup.kt:262-279` (`"Configure servers manually"` = `34dp` tall), `367-385` (`Segmented` option = `38dp` tall)
- **What's wrong:**
  - Because custom `Box`/`Row` + `.clickable` is used instead of M3 `minimumInteractiveComponentSize()`, touch targets are restricted to their visual bounds.
  - Most critically, in `ThreadScreen.kt:383-387`, `"to " + recipientsSummary(message)` is a `12.sp` (`16.sp` line-height) `Text` with `0.dp` vertical padding sitting directly inside the clickable `MessageCard` header row — tapping `"to Alice"` to inspect sender/recipient details (`HeaderDetails`, our primary anti-phishing affordance!) almost always misses the 16dp text strip and collapses the message card instead.
- **Concrete fix:**
  1. In `DotIconButton` (`Basics.kt:139`), use `Modifier.minimumInteractiveComponentSize().size(44.dp)` (or `.size(48.dp)`).
  2. In `SendButton` (`ComposeScreen.kt:234`), add `.minimumInteractiveComponentSize()`.
  3. In `ThreadScreen.kt:383-387`, give the `"to …"` details toggle at least `vertical = 6.dp` padding + a trailing chevron icon (`Icons.Outlined.ExpandMore` / `ExpandLess`) or `minimumInteractiveComponentSize()`.
  4. In `PlainTextBody` (`ThreadScreen.kt:509`), `RecipientChip` (`ComposeScreen.kt:331`), and `"Cc Bcc"` (`ComposeScreen.kt:158`), add `minimumInteractiveComponentSize()` or increase vertical hit padding.

---

### P1-3 · Fixed heights and widths clip text under system font scaling (1.5x–2.0x)
- **Where:**
  - `designsystem/src/main/kotlin/app/dot/design/components/Bars.kt:53` (`DotTopBar` fixed `.height(56.dp)`)
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxScreen.kt:574` (`ComposeButton` fixed `.height(54.dp)`)
  - `app/src/main/kotlin/app/dot/mail/ui/compose/ComposeScreen.kt:250,273` (`FieldRow` / `RecipientRow` label fixed `.width(44.dp)`)
  - `app/src/main/kotlin/app/dot/mail/ui/thread/ThreadScreen.kt:458` (`HeaderDetails` label fixed `.width(52.dp)`)
  - `app/src/main/kotlin/app/dot/mail/ui/account/AccountSetup.kt:349` (`ServerFields` Port field fixed `.width(92.dp)`)
- **What's wrong:**
  - `DotTopBar` (`Bars.kt:53`) uses `.height(56.dp)`. When both `title` (`25.sp` line height) and `subtitle` (`16.sp` line height) are present (as on `InboxScreen`), at 1.5x–2.0x font scale their combined height is `62–82dp`, completely clipping the subtitle.
  - In `ComposeScreen.kt:250,273`, `.width(44.dp)` on `14.sp` labels (`"From"`, `"Bcc"`) forces mid-word wrapping (`"Fro\nm"`) at ≥ 1.35x font scale.
  - In `AccountSetup.kt:349`, `.width(92.dp)` on `DotTextField` (which has `32.dp` horizontal padding) leaves only `60.dp` for port numbers, clipping `"Port"` / digits at large font scales.
- **Concrete fix:**
  ```kotlin
  // Bars.kt:53
  Row(
      Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = DotSpacing.xs, vertical = DotSpacing.xs),
      verticalAlignment = Alignment.CenterVertically,
  )
  // ComposeScreen.kt:250, 273
  Text(label, style = Dot.type.callout, color = Dot.colors.textTertiary, modifier = Modifier.widthIn(min = 48.dp).padding(end = DotSpacing.s))
  // AccountSetup.kt:349
  DotTextField(port, onPort, Modifier.widthIn(min = 96.dp).weight(0.35f), label = "Port", keyboardType = KeyboardType.Number)
  ```

---

### P1-4 · Broken TalkBack semantics across `ThreadRow`, `Segmented`, `ToggleRow`, `MailDrawer`, and text inputs
- **Where:**
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxScreen.kt:415-439,457,482,578-579`
  - `app/src/main/kotlin/app/dot/mail/ui/account/AccountSetup.kt:367-385`
  - `app/src/main/kotlin/app/dot/mail/ui/settings/SettingsScreen.kt:90-97,199-209`
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/MailDrawer.kt:92,142,158,163,185-186`
  - `designsystem/src/main/kotlin/app/dot/design/components/Inputs.kt:88-127`
- **What's wrong:**
  1. **`ThreadRow` (`InboxScreen.kt:431`):** The outer row has `.combinedClickable(...)` and `.semantics(mergeDescendants = true)`, while the inner Avatar `Box` ALSO has `.combinedClickable(onClick = onLongClick)` with no `contentDescription` or `onClickLabel`. Compose semantics splits any clickable child into a separate accessibility node, so TalkBack focuses twice on every row and reads the avatar initials as an unlabelled button (*"AK, double tap to activate"*). Additionally, `"Starred"` is announced twice (once via `stateDescription` at line 418 and once via `Icon(..., "Starred")` at line 482), and `thread.messageCount` (line 457) is read as a bare number (`"3"`) without `"messages"`.
  2. **`Segmented` (`AccountSetup.kt:375-382`):** Options use `.clickable { onSelect(value) }` with no `role = Role.RadioButton` and no `semantics { selected = isSel }`. Screen-reader users cannot tell which theme, density, or TLS mode is selected.
  3. **`ToggleRow` (`SettingsScreen.kt:199-209`):** Both `ListRow` (`onClick`) and `DotSwitch` (`onCheckedChange`) are independently clickable, and the row lacks `Modifier.toggleable(value = checked, role = Role.Switch, ...)`. TalkBack focuses the row without reading its On/Off state, then focuses the unlabelled switch separately.
  4. **`MailDrawer` (`MailDrawer.kt:142,158,163,185`):** Account rows (`line 142`) set `role = Role.Tab` without `semantics { selected = (a.id == state.focusedAccountId) }`. Conversely, `DrawerItem` (`line 185-186`) hardcodes `role = Role.Tab` and `semantics { this.selected = selected }`, so action items `"Add account"`, `"Settings"`, and `"Outbox"` are announced by TalkBack as *"Settings, not selected, tab"*.
  5. **`DotTextField` (`Inputs.kt:90,107`) & `Plain` (`ComposeScreen.kt:392-407`):** Labels and placeholders are sibling `Text` composables outside `BasicTextField` with no `semantics { contentDescription = ... }`, so focusing an empty field in TalkBack announces only *"Edit box"*.
- **Concrete fix:**
  ```kotlin
  // AccountSetup.kt:375 (Segmented)
  Box(
      Modifier.weight(1f).clip(DotShapes.small)
          .background(if (isSel) c.surfaceRaised else Color.Transparent)
          .selectable(selected = isSel, role = Role.RadioButton, onClick = { onSelect(value) })
          .padding(vertical = 10.dp),
      contentAlignment = Alignment.Center,
  )
  ```
  ```kotlin
  // InboxScreen.kt:431 (ThreadRow avatar) & 482 (Star icon)
  Box(
      Modifier.clip(CircleShape).clickable(
          onClickLabel = if (selected) "Deselect conversation" else "Select conversation",
          onClick = onLongClick,
      ).semantics { contentDescription = if (selected) "Selected" else "Select ${thread.participants.lastOrNull()?.displayName ?: "conversation"}" }
  )
  // Line 482: pass null contentDescription since stateDescription already includes "Starred."
  Icon(Icons.Outlined.Star, contentDescription = null, Modifier.size(16.dp), tint = c.star)
  ```

---

### P1-5 · `MainActivity` triggers `BiometricPrompt.authenticate()` in the background during `onStop()` and fails to auto-prompt on resume
- **Where:** `app/src/main/kotlin/app/dot/mail/MainActivity.kt:66-69,88-91`
- **What's wrong:**
  - When the user leaves the app or turns off the screen, `onStop()` sets `locked.value = true` (`MainActivity.kt:90`).
  - Because the Compose composition is still active as the Activity transitions to stopped, setting `locked.value = true` immediately recomposes `setContent` into the `if (isLocked)` branch (`line 66`) and runs `LaunchedEffect(Unit) { authenticate() }` (`line 68`) **while the app is going into the background**.
  - `BiometricPrompt.authenticate()` fails/cancels immediately because the Activity is not in the foreground. When the user later returns to the app (`onStart`/`onResume`), `isLocked` is already `true`, so `LaunchedEffect(Unit)` does **not** re-run and the user is not prompted until they manually tap `"Unlock"`.
- **Concrete fix:**
  Trigger `authenticate()` only when the Activity lifecycle is at least `STARTED`:
  ```kotlin
  // MainActivity.kt:66-69
  if (isLocked) {
      val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
      LockScreen(onUnlock = ::authenticate)
      LaunchedEffect(lifecycleOwner) {
          lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
              authenticate()
          }
      }
  }
  ```

---

### P1-6 · Pull-to-refresh finishes before sync starts, initial sync shows `"All caught up"`, and `SyncState.Error` messages are never shown
- **Where:**
  - `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt:47-51,205`
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxViewModel.kt:49-50,129-137`
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxScreen.kt:196-208,271-280`
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/MailDrawer.kt:152-154`
- **What's wrong:**
  1. **Pull-to-refresh spinner vanishes in `< 1ms`:** `RealMailGraph.refresh(mailbox)` (`RealMailGraph.kt:205`) just calls `requestSync()`, which launches a background coroutine with a 300ms debounce to enqueue WorkManager and returns immediately. In `InboxViewModel.refresh()` (`InboxViewModel.kt:132-135`), `_refreshing.value` flips `true` → `false` before network sync has even started, and `.onFailure` is unreachable dead code.
  2. **First-run empty state lies during initial sync:** Right after adding an account, the user navigates to `InboxScreen`. Room Initially has 0 threads (`threads.loadState.refresh` is `NotLoading` after the empty local query completes in ~5ms), while background IMAP sync is still running (`Account.syncState == SyncState.Syncing`). `InboxScreen` displays **"All caught up — New mail will appear here"** instead of a syncing state.
  3. **Sync errors are hidden:** `Sync.kt:81-82` and `RealMailGraph.kt:272` populate `Account.syncState = SyncState.Error(message)` with important user-facing messages (`"Authentication failed"`, `"A send could not be confirmed. Check Sent before retrying."`, `"Some server changes are pending or require review"`), as documented in `ARCHITECTURE.md` §4. Yet `SyncState.Error.message` is **never rendered anywhere in `:app`** — only a tiny 18dp icon appears inside the closed drawer (`MailDrawer.kt:153`) with no text.
- **Concrete fix:**
  1. Derive `refreshing` in `InboxViewModel` from `_refreshing` combined with `accounts.any { it.syncState is SyncState.Syncing }`, and show `SkeletonList()` when `threads.itemCount == 0` while any relevant account is `SyncState.Syncing`.
  2. Render a quiet banner at the top of `InboxScreen` (and under the account in `MailDrawer` / `SettingsScreen`) when an active account is in `SyncState.Error(message)`.

---

### P1-7 · Focusing another account in `MailDrawer` while viewing an `InFolder` mailbox blanks the Inbox header and breaks `"Move to"`
- **Where:**
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxViewModel.kt:60-86`
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxScreen.kt:246`
- **What's wrong:**
  - `InboxViewModel.header` (`lines 76-84`) resolves `Mailbox.InFolder(box.folderId)` by searching `d.folders` (`drawer.folders`), which **only contains folders of `focusedAccountId`**.
  - If a user with 2 accounts opens a folder of Account 1 (e.g. `Mailbox.InFolder(10)`), then opens the drawer and taps Account 2 to inspect Account 2's folders (`onFocusAccount(2)`), `d.folders` switches to Account 2's folders.
  - Immediately:
    1. `d.folders.firstOrNull { it.id == 10 }` returns `null`, so **`header.title` becomes `""`, `header.subtitle` becomes `null`, and `header.role` becomes `null`** (blank top bar).
    2. Opening the `"Move to"` bottom sheet (`InboxScreen.kt:246`) lists `drawer.folders` (Account 2's folders) for threads belonging to Account 1 (or mixed accounts in Unified Inbox), causing `RealMailGraph.move` to crash with `"Moving between accounts is unsupported"`.
- **Concrete fix:**
  Observe folders for the current `InFolder` mailbox's account independently of `focusedAccount` (or observe all folders across accounts in `InboxViewModel`), and filter the `"Move to"` sheet to folders matching the selected threads' `accountId` (disabling `"Move to…"` when threads from multiple accounts are selected simultaneously).

---

### P1-8 · Day-1 gaps in `ThreadScreen`: `mailto:` query stripping, broken `mailto:` Unsubscribe, missing Move / Delete-forever / Not-spam, and no attachment sharing
- **Where:** `app/src/main/kotlin/app/dot/mail/ui/thread/ThreadScreen.kt:238-255,289-293,306,537-562`
- **What's wrong:**
  1. **`mailto:` links drop `subject`, `body`, `cc`, `bcc` (`ThreadScreen.kt:290`):**
     `if (url.startsWith("mailto:", ignoreCase = true)) onComposeTo(url.removePrefix("mailto:").substringBefore('?'))` strips all RFC 6068 query parameters instead of using `MailtoParser.parse(url)` (`MainActivity.kt:153`).
  2. **`Unsubscribe` menu item breaks on `mailto:` `List-Unsubscribe` headers (`ThreadScreen.kt:250-252`):**
     `MenuItem("Unsubscribe", ...) { menu = false; pendingLink = target }` routes `mailto:` unsubscribe targets into `LinkDialog`, which displays an empty host and launches an external `ACTION_VIEW` intent instead of opening `ComposeScreen` with the parsed `mailto:` recipient and unsubscribe subject/body.
  3. **Missing folder/context actions in `ThreadScreen` (`ThreadScreen.kt:238-255`):**
     Unlike `SelectionBar` in `InboxScreen`, `ThreadScreen` has no `Move to…` action, no `Delete forever` when viewing a message in Trash, and no `Not spam` when viewing a message in Spam.
  4. **Attachment sharing is wired in `ThreadViewModel` but unreachable in UI (`ThreadScreen.kt:169,547`):**
     `ThreadViewModel.openAttachment(a, share)` supports `share: Boolean`, but `AttachmentStrip` only ever calls `onOpen(a, false)`.
- **Concrete fix:**
  - Change `onComposeTo: (String) -> Unit` on `ThreadScreen` to `onComposeMailto: (ExternalRequest.Compose) -> Unit` backed by `MailtoParser.parse(url)`, and check `target.startsWith("mailto:", ignoreCase = true)` in the `"Unsubscribe"` menu item before setting `pendingLink`.
  - Add a share icon button or long-press action on attachment chips in `AttachmentStrip` calling `onOpen(a, true)`.

---

### P1-9 · `undoSendSeconds` is read non-reactively via `.ui.value`, so changing Undo Send in Settings has no effect until app restart
- **Where:**
  - `app/src/main/kotlin/app/dot/mail/ui/Navigation.kt:175`
  - `app/src/main/kotlin/app/dot/mail/ui/compose/ComposeScreen.kt:91`
- **What's wrong:**
  - `UndoSendHost` (`Navigation.kt:175`) reads `val seconds = LocalSettings.current.ui.value.undoSendSeconds` without `.collectAsStateWithLifecycle()`. Because `UndoSendHost` is composed once at the root of `DotMailRoot` and never leaves composition, changing `"Undo send"` in `SettingsScreen` (e.g. from `5 seconds` to `Off` or `30 seconds`) never updates `seconds` in `UndoSendHost`.
  - Similarly, `ComposeScreen.kt:91` reads `LocalSettings.current.ui.value.undoSendSeconds` once and passes it as a constructor `Int` to `ComposeViewModel`.
- **Concrete fix:**
  ```kotlin
  // Navigation.kt:175
  val prefs by LocalSettings.current.ui.collectAsStateWithLifecycle()
  val seconds = prefs.undoSendSeconds
  ```

---

### P1-10 · `ComposeViewModel` autosave loop triggers redundant saves, and every keystroke recomposes the entire `ComposeScreen`
- **Where:** `app/src/main/kotlin/app/dot/mail/ui/compose/ComposeViewModel.kt:98-100,223-229`
- **What's wrong:**
  - In `ComposeViewModel.kt:98-100`:
    ```kotlin
    _state.drop(1).debounce(1_500).collect { s ->
        if (!finished && !_closed.value && !s.isEmpty) save()
    }
    ```
  - When `save()` completes (`line 228`), it updates `_state`:
    ```kotlin
    _state.update { s -> s.copy(draft = s.draft?.copy(localId = id), savedOnce = true) }
    ```
    Because `_state` is a single `MutableStateFlow<ComposeState>`, that update emits a new `ComposeState` into `_state`, which triggers `debounce(1_500)` **a second time** 1.5s later! Furthermore, every contact autocomplete emission (`suggestions` / `suggestFor`) or clicking `"Cc Bcc"` (`showCcBcc = true`) also mutates `_state` and triggers a disk/DB draft save + `cacheAttachments` pass.
- **Concrete fix:**
  ```kotlin
  // ComposeViewModel.kt:98-100
  _state
      .map { it.draft?.copy(localId = 0) }
      .distinctUntilChanged()
      .drop(1)
      .debounce(1_500)
      .collect { d ->
          if (!finished && !_closed.value && d != null && !_state.value.isEmpty) save()
      }
  ```

---

### P1-11 · `HtmlMessageView` does not update dark mode or `onLink` in `update {}`, and shrinks wide HTML emails while disabling zoom
- **Where:** `app/src/main/kotlin/app/dot/mail/ui/thread/HtmlMessageView.kt:43,60-63,70-89,110`
- **What's wrong:**
  1. **Dark mode not updated in `update`:** `WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, dark)` is only called inside `factory` (`line 71`), never inside `update` (`line 76`). If the user changes theme mode while a WebView exists, algorithmic darkening stays stuck in the old mode.
  2. **Unreadable wide newsletters (`lines 60-63`):** `useWideViewPort = true` and `loadWithOverviewMode = true` scale 600px–800px table-based HTML newsletters down to fit the phone width (~0.5x scale, making text ~7.5px), while `setSupportZoom(false)` (`line 63`) **prevents the user from pinch-zooming** to read the scaled-down email.
  3. **Stale `onLink` lambda (`lines 79-83`):** `SafeClient` is only recreated when `allowRemote` or `inlineUris` changes, not when `onLink` changes.
  4. **Nullable `InputStream` (`line 110`):** `resolver.openInputStream(request.url)` can return `null`, which should fall back to `EMPTY()`.
- **Concrete fix:**
  - Enable pinch-to-zoom (`setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false`) so users can zoom wide HTML tables without showing ugly chrome controls.
  - Update `SafeClient.onLink` and `WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, dark)` inside `update {}`.

---

### P1-12 · Quiet UI & Material fallbacks: `DotSwitch` lacks `enabled` and disabled token mapping; notifications use legacy Android 2.x drawables
- **Where:**
  - `designsystem/src/main/kotlin/app/dot/design/components/Inputs.kt:190-204`
  - `app/src/main/kotlin/app/dot/mail/ui/settings/SettingsScreen.kt:131,207`
  - `data/src/main/kotlin/app/dot/mail/data/MailNotifications.kt:30-33`
- **What's wrong:**
  1. **`DotSwitch` (`Inputs.kt:190`):** Has no `enabled: Boolean = true` parameter and does not map `disabledChecked*` / `disabledUnchecked*` slots in `SwitchDefaults.colors(...)`. In `SettingsScreen.kt:131` (`App lock`), when `canLock == false` (no device screen lock set), the row title is dimmed, **but the `DotSwitch` remains visually active and animates on touch**.
  2. **Legacy notification icons (`MailNotifications.kt:30-33`):** Uses `android.R.drawable.ic_dialog_email`, `ic_menu_view`, and `ic_menu_save` (a 2009 Gingerbread floppy-disk icon for `"Archive"`!).
- **Concrete fix:**
  ```kotlin
  // Inputs.kt:190
  @Composable
  fun DotSwitch(
      checked: Boolean,
      onCheckedChange: ((Boolean) -> Unit)?,
      modifier: Modifier = Modifier,
      enabled: Boolean = true,
  ) {
      val c = Dot.colors
      Switch(
          checked = checked,
          onCheckedChange = onCheckedChange,
          enabled = enabled,
          modifier = modifier,
          colors = SwitchDefaults.colors(
              checkedThumbColor = c.onInverse,
              checkedTrackColor = c.inverse,
              checkedBorderColor = c.inverse,
              uncheckedThumbColor = c.textTertiary,
              uncheckedTrackColor = c.surfaceRaised,
              uncheckedBorderColor = c.borderStrong,
              disabledCheckedThumbColor = c.onInverse.copy(alpha = 0.5f),
              disabledCheckedTrackColor = c.surfaceRaised,
              disabledCheckedBorderColor = c.border,
              disabledUncheckedThumbColor = c.borderStrong,
              disabledUncheckedTrackColor = c.surface,
              disabledUncheckedBorderColor = c.border,
          ),
      )
  }
  ```

---

### P1-13 · `OutboxScreen` omits `navigationBarsPadding()`, lacks a root `Box`, and does not elevate `DotTopBar` on scroll
- **Where:** `app/src/main/kotlin/app/dot/mail/ui/outbox/OutboxScreen.kt:53-96,121-128`
- **What's wrong:**
  - Line 128 defines `private fun Modifier.androidx_navPad() = this.then(Modifier.padding(bottom = 24.dp))` instead of calling `.navigationBarsPadding()`, and `LazyColumn` (`line 59`) has no `navigationBarsPadding()` or bottom `contentPadding`.
  - `Column` (`line 53`) and `OutboxSnack` (`line 96`) are emitted without a wrapping `Box(Modifier.fillMaxSize())`, and when `items.isEmpty()` (`line 57`), `return@Column` runs before `LazyColumn` while `DotTopBar` (`line 54`) never passes `elevated = listState.canScrollBackward`.
- **Concrete fix:**
  Wrap `OutboxScreen` in `Box(Modifier.fillMaxSize().background(c.background))`, pass `rememberLazyListState()` (`elevated = listState.canScrollBackward`) to `DotTopBar` and `LazyColumn`, and replace `Modifier.androidx_navPad()` with `Modifier.navigationBarsPadding().padding(bottom = 8.dp)`.

---

## P2 — Polish / Consistency / RTL / Minor Performance

### P2-1 · RTL layout issues in `Navigation`, `SettingsScreen`, and `HtmlMessageView`
- **Where:**
  - `app/src/main/kotlin/app/dot/mail/ui/Navigation.kt:162-168`
  - `app/src/main/kotlin/app/dot/mail/ui/settings/SettingsScreen.kt:118-119`
  - `app/src/main/kotlin/app/dot/mail/ui/thread/HtmlMessageView.kt:137`
- **What's wrong:**
  - `Navigation.kt:162-168` uses `slideInHorizontally { it / 8 }` and `slideOutHorizontally { it / 8 }`, which always slide from the physical right even when `LayoutDirection.Rtl` is active (`android:supportsRtl="true"` is enabled in `AndroidManifest.xml:15`).
  - `SettingsScreen.kt:118-119` labels `swipeStart` (`StartToEnd`) as `"Swipe right"` and `swipeEnd` (`EndToStart`) as `"Swipe left"`, which are reversed in RTL.
  - `HtmlMessageView.kt:137` styles `blockquote` with `padding-left:12px;border-left:2px solid ...` instead of logical `padding-inline-start:12px;border-inline-start:2px solid ...`.
- **Concrete fix:**
  Use `slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, ...)` / `slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, ...)` in `Navigation.kt:162-168`, and `padding-inline-start` / `border-inline-start` in `HtmlMessageView.kt:137`.

---

### P2-2 · Design-system hygiene: `DotMenu`/`MenuItem` placed in `:app`, `MenuItem` icon tint ignores `color`, and disabled `DotButton` shows an unintended border
- **Where:**
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxScreen.kt:547-566`
  - `app/src/main/kotlin/app/dot/mail/ui/compose/ComposeScreen.kt:125`
  - `designsystem/src/main/kotlin/app/dot/design/components/Basics.kt:89,102`
- **What's wrong:**
  1. `DotMenu` and `MenuItem` are defined in `app.dot.mail.ui.inbox` (`InboxScreen.kt:547-566`) and imported across `ComposeScreen`, `SettingsScreen`, and `ThreadScreen` instead of living in `:designsystem` (`app.dot.design.components`).
  2. In `MenuItem` (`InboxScreen.kt:563`), `leadingIcon` hardcodes `tint = Dot.colors.textSecondary` instead of using `color` when `color != Dot.colors.textPrimary` — so `MenuItem("Discard", Icons.Outlined.DeleteOutline, color = c.danger)` (`ComposeScreen.kt:125`) renders red text next to a grey icon.
  3. In `DotButton` (`Basics.kt:102`), `.border(DotSpacing.hairline * 2, if (enabled) outline else c.border, DotShapes.medium)` draws a `1.dp` border on disabled `Primary` and `Ghost` buttons even though their enabled `outline` is `Color.Transparent`.
- **Concrete fix:**
  - Move `DotMenu` and `MenuItem` to `:designsystem`, tint `MenuItem`'s icon with `if (color == Dot.colors.textPrimary) Dot.colors.textSecondary else color`, and in `Basics.kt:102` use `if (enabled || outline == Color.Transparent) outline else c.border`.

---

### P2-3 · `SearchScreen` ignores user's `Compact` density setting and lacks loading/error states
- **Where:** `app/src/main/kotlin/app/dot/mail/ui/search/SearchScreen.kt:94-105`
- **What's wrong:**
  - Line 102 hardcodes `compact = false` on `ThreadRow(...)` instead of reading `prefs.density == Density.COMPACT`.
  - When `q.length >= 2` and `results.itemCount == 0`, `LoadState.Loading` and `LoadState.Error` fall through to an empty `LazyColumn` with no feedback.
- **Concrete fix:**
  Read `val prefs by LocalSettings.current.ui.collectAsStateWithLifecycle()`, pass `compact = prefs.density == Density.COMPACT`, and handle `results.loadState.refresh is LoadState.Loading` / `LoadState.Error`.

---

### P2-4 · Recomposition & animation hot spots in `SettingsScreen`, `SkeletonList`, `PlainTextBody`, and `DotTheme`
- **Where:**
  - `app/src/main/kotlin/app/dot/mail/ui/settings/SettingsScreen.kt:87`
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxScreen.kt:591-615`
  - `app/src/main/kotlin/app/dot/mail/ui/thread/ThreadScreen.kt:505,515`
  - `designsystem/src/main/kotlin/app/dot/design/Theme.kt:38`
- **What's wrong:**
  1. `SettingsScreen.kt:87` passes `elevated = scroll.value > 0` directly, reading `scroll.value` on every scroll frame and recomposing the entire `SettingsScreen`. Use `val elevated by remember { derivedStateOf { scroll.value > 0 } }` (or `scroll.canScrollBackward`).
  2. `SkeletonList` (`InboxScreen.kt:593`) instantiates 8 separate `rememberInfiniteTransition` animations (one per `SkeletonRow`) instead of hoisting a single `pulse` value in `SkeletonList`.
  3. `PlainTextBody` (`ThreadScreen.kt:505,515`) runs `linkify(...)` (regex scan + `AnnotatedString` allocation) directly in composition without `remember(split.visible, c.accent, onLink)`.
  4. `DotTheme` (`Theme.kt:38`) allocates `DotTypography()` on every recomposition instead of referencing a top-level `private val DefaultDotTypography = DotTypography()`.

---

### P2-5 · `Account.colorIndex` and `Identity.signature` are defined in the data contract but unused in the UI
- **Where:**
  - `data/src/main/kotlin/app/dot/mail/data/model/Models.kt:14,24`
  - `app/src/main/kotlin/app/dot/mail/ui/inbox/InboxScreen.kt:393-491`
  - `app/src/main/kotlin/app/dot/mail/ui/compose/ComposeViewModel.kt:69-93`
- **What's wrong:**
  - `Account.colorIndex` (`Models.kt:14`) is persisted per account, but `InboxScreen` shows no subtle account indicator on rows in `"All inboxes"`, making it impossible to tell which account received a thread before opening it.
  - `Identity.signature` (`Models.kt:24`) is never appended to new drafts/replies in `ComposeViewModel` and cannot be edited in `SettingsScreen`.
- **Concrete fix:**
  Append `sender.signature` (when non-blank) to new drafts/replies in `ComposeViewModel`, and expose a per-account detail sheet/dialog in `SettingsScreen` for editing display name and signature.
