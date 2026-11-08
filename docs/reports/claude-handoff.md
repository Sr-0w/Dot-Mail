# Lead handoff (Claude) — 2026-11-08 08:35

## State
- main is green (`scripts/gw :mail:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`).
- **v0.1.0-alpha.1 published** (GitHub Releases, signed with the project key, cert SHA-256 12c122aa…f4).
  `scripts/release.sh <tag> [--prerelease]` now exports the signing env and pins build-tools 35.0.0.
- Workflow: each agent has a worktree `../wt-<agent>` on `<agent>/work`; PR → Claude squash-merges.
  `scripts/gw` builds each worktree in its own host dir (`~/dotmail`, `~/dotmail-wt-<agent>`).
- Room schema: v6 (snoozed). Codex's draft mirroring will be v7.

## Done this session (merged)
HTML fit-to-screen + sanitised stylesheets (security-reviewed by Gemini, #15) · refresh spinner hand-off to
header progress line + haptic · swipe between conversations (opt-in) · two-pane inbox ≥840dp · calendar invite
cards (Qwen UI + Ics) · link dialog with IDN/homograph warning and tracking-param stripping · one-click
unsubscribe (RFC 8058) · PDF preview · folder management (Sonnet, verified live) · persistent remote-image
permissions (Sonnet) · unthreaded list (Sonnet) · per-account settings (Sonnet) · snooze (Sonnet) · scoped
moves (Codex #16, thread route carries the source mailbox) · TLS 1.2+/cert errors (Codex #17) · updatePassword
(Codex #19) · button hierarchy + motion pass + Cc/Bcc animation (Gemini) · FR/DE localisation started (Gemini)
· engine helpers by Qwen: Links, ListUnsubscribe, Classifier, SnoozePresets, Templates; RELEASES reproducible
builds, FDROID.md, de-DE store text.

## In flight
- Codex: draft mirroring (v7) → iTIP reply (`sendCalendarReply`, then wire InviteCard buttons, myEmail) →
  instant-notification FGS data API.
- Sonnet: "Sign in again" dialog → snoozed wake time on rows → attachment browser.
- Gemini: localisation of inbox/drawer/search/outbox/snooze/widget/notifications/Format.
- Qwen: scripts/reprotest.sh (J7).

## Needs the phone (only Claude; check foreground/lock first — see memory personal-phone-guard)
Phone was locked/dozing all morning: nothing visually verified since the alpha. To verify when the owner
is not using it: Infomaniak device-update mail fits the screen (compare Samsung Email), refresh lifecycle,
remote-image remember, swipe navigation, PDF preview, Cc/Bcc animation, two-pane in landscape.
Pinch-to-zoom for HTML is deliberately NOT built yet: needs on-device gesture testing inside the LazyColumn.

## Next candidates (FEATURE_REQUESTS)
Templates UI in composer (engine ready), classifier wiring (needs headers at sync), notification rules /
mute, contacts picker (no blanket permission), settings backup/export, newsletter view, pinch-to-zoom.
