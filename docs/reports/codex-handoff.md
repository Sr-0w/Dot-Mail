# Codex handoff — 2026-11-08

Worktree: `/home/srobin/Documents/Dot apps/wt-codex`, branch `codex/work`. Main tree and phone are Claude-only. Build with `scripts/gw`; never copy live credentials into the repository. Focused PRs are squash-merged by Claude; fetch/rebase before continuing and lease-protect pushes to this branch.

Delivered and merged during this block:

- #16 — mailbox-scoped apply/move, deprecated legacy overloads, InboxViewModel wiring, typed ambiguity refusal and regression tests.
- #17 — TLS1.3/1.2 allowlist intersected with platform support, debug off, safe certificate classification. Android26–28 remains TLS1.2-compatible.
- #19 — AccountRepository.updatePassword verifies both servers before encrypted replacement/error clearing; failed verification preserves prior state.
- #24 — Room v7 draft mirroring, durable revision/APPEND recovery/tombstones, external conflict preservation, send/discard/undo, v6 snooze preservation. See codex-draft-mirroring.md for the delayed-search duplicate limitation.
- #26 — ComposeRepository.sendCalendarReply(messageId,Ics.PartStat):Long, organizer-only invited-identity iTIP alternative, durable outbox and 5s undo.
- #27 — nullable Category on Message/ThreadSummary, Message.listId, virtual NEWSLETTERS, header signals/PDF detection and bounded cache backfill. Claude added the drawer entry on main.

Final PR in this block: optional instant notifications. See codex-instant-notifications.md. Public MailRepository hooks are `instantNotifications: Flow<Boolean>` and `setInstantNotificationsEnabled(Boolean): Boolean`. No new schema/dependency. The unexported specialUse service uses one graph-owned IDLE coordinator, preserving sockets across foreground/background handoffs, with network/auth reconciliation and retry. Immediate WorkManager jobs now explicitly use scheduled=false so periodic interval/manual-only gating cannot suppress IDLE events. Periodic jobs still honor those preferences.

Validation: full scripts/gw :mail:test :data:testDebugUnitTest :app:assembleRelease passed (R8). Focused IDLE/service tests plus release are rerun on the final rebased source before PR. GreenMail verifies real IDLE delivery/unread/cancellation; Robolectric API34 verifies service/API/manifest/handoff/worker behavior. No phone or live mailbox access. The service tests replace Main with UnconfinedTestDispatcher because runBlocking otherwise deadlocks Robolectric's paused main looper; calendar attachment tests reset FileProvider's static path cache between method-specific files directories.

Next steps for Claude: wire the Settings toggle/explanation after merging the final PR; enable while the app is visible, background the app, send a fixture to the real account, verify notification and folder-scoped actions, exercise Stop/disable/network change/reauthorization, and check OEM/Doze behavior. No agent should install or operate the phone. Instant mode restores when the app is next opened after reboot (no boot receiver); Android battery/network limits still apply. No implementation is intentionally half-finished in this block.

## Follow-up: refresh performance/recovery

See codex-refresh-performance.md. Read-only Infomaniak warm full refresh improved from 17.1–18.3s to 3.02–3.05s (four logins), or 1.19–1.25s with a warmed IDLE pool (zero logins). Inbox commit is 1.59–1.61s. syncAll was 60,636 DEX units and is now 412; phases split into small methods. Added Error.errorKind/canRetry and MailRepository.retrySync(accountId), retained sanitized diagnostics, and tested late tail errors. No phone access. Claude will wire banners and measure the phone. Next queued task from Claude: emoji/CJK/combining-mark snippet corruption, surrogate-safe truncation, and healing stored snippets. Do that as a separate PR after this refresh PR.
