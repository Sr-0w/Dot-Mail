# Lead handoff — 2026-11-08 05:10

State of `main`: builds; app runs on the owner's phone with a real Infomaniak account (sync, read, send verified).
Known failing tests: `:mail` IcsTest (Qwen, calendar, in progress on qwen/work).

Workflow: per-agent worktrees `Dot apps/wt-<agent>` on `<agent>/work`; PRs into main, Claude merges (docs/TEAM.md).
Agents at usage limit: Codex (back ~08:17), Sonnet, Claude. Gemini (TLS hardening + compose bodyHtml wiring) and
Qwen (calendar .ics parser) may still be working in their worktrees.

Next steps, in order:
1. Release smoke test: `build-out/apk/release/app-release.apk` (R8, debug-signed) is built but NOT yet run on the
   phone — install with `scripts/emu install <apk>` only when the phone is idle; check `DotMail/Sync` logs.
   Then `scripts/release.sh v0.1.0-alpha.1 --prerelease` (signing key in ~/.config/dotmail, never in git).
2. Codex: review2 P0-6 (thread moves must only move messages in the current folder, not Sent copies), remaining
   review2 items, then OAuth docs, instant-notification mode. Use wt-codex.
3. Sonnet: folder management — fix FolderManagementDataTest (use nested names; GreenMail top-level RENAME/DELETE
   NPE), verify RENAME/DELETE replay, then drawer UI (long-press Rename/Move/Delete, "New folder") + demo data.
4. Gemini: wire Draft.bodyHtml in compose, finish TLS audit + send MailClient diffs to Codex.
5. Claude: review/merge PRs; FEATURE_REQUESTS.md next items (fetch+merge before editing — owner edits on GitHub).
