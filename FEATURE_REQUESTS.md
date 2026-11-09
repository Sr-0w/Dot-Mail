# Feature requests

The owner's backlog and the team's tracker. The owner edits this file on GitHub: `git pull --rebase` before committing.

## How this file works

- **Where things live**: `Open` (not started, or work not active) → `In progress` (active, or merged but not fully verified) →
  `Done` (every scoped acceptance criterion implemented **and** verified). Within each, items are grouped by feature area.
- **Done means verified.** A merged PR alone is not Done. Each Done item names its evidence (PR/commit, tests, device check).
  Anything not proven stays In progress with the exact gap. Never claim a device check that nobody did.
- **Reopen** a Done item when it regresses or turns out incomplete; say what is missing and link the evidence.
- **Format** of an item (keep it this short, no inline diaries; put long detail in a PR, an issue or `docs/reports/`):

  ```
  - **FR-nn · P1 · Title** — one-line goal.
    - [x] / [ ] acceptance criteria (only when useful)
    - Evidence: PR #n, tests. Device: checked / pending. Gaps: …
  ```

- **IDs** (`FR-nn`) are stable: never renumber, never reuse. New requests take the next free number.
- **Priority**: `P1` next/blocking · `P2` soon · `P3` nice to have. **PR numbers** are
  `https://github.com/Sr-0w/Dot-Mail/pull/N`. **Device** = the owner's Galaxy S25 Ultra unless stated.
- **Re-audit** the whole file regularly (after significant merges, before each release, after any regression report) —
  see FR-76. Never lose a user requirement during cleanup.

Last audit: 2026-11-08 (against `main` ff5f441, the code, the tests and the PR list; no device was used for this audit —
"Device: pending" lines are things someone still has to check on a phone).

---

## Open

### Reading and HTML mail

- **FR-21 · P1 · Severe HTML email flicker / blanking while scrolling** — real-device regression (S25 Ultra, image-heavy
  mail such as AliExpress): large areas disappear to black/blank and reappear while header and floating buttons stay.
  Photosensitivity and usability issue, not cosmetic.
  - [ ] No flicker, disappearing content, layout jumps or unstable rendering while scrolling, including with floating
    headers and the top blur
  - [ ] Verified on device with long image-heavy mails, fast/slow and repeated scrolling (not from screenshots or unit tests)
  - Evidence: none yet. Related: FR-54 (top blur flicker).
- **FR-22 · P1 · Android system Back crashes when leaving an open email** — repro on S25 Ultra: open a message, press
  Back/gesture; the app crashes instead of returning.
  - [ ] Real stack trace/logcat captured and inspected; root cause fixed
  - [ ] Back returns to the previous screen in every case: after HTML scrolling/loading, attachment views,
    swipe-between-conversations, with keyboard or a modal open; in-app Back arrow too
  - [ ] Regression tests for the navigation/lifecycle/WebView-disposal paths, then device verification
  - Evidence: none (`0196e79` only fixes Back closing the drawer, which is unrelated).

### Sending

- **FR-11 · P2 · Complete delayed / scheduled sending ("Send later")** — only three presets exist today
  (this evening / tomorrow morning / Monday morning).
  - [x] Custom future date and time picker; show the scheduled time and timezone (`ScheduleSheet`, #59)
  - [x] Queued messages can be viewed, edited, rescheduled and cancelled before SMTP submission (Outbox Edit / Reschedule,
    #59; claimed messages are locked)
  - [ ] Robust across restart, connectivity changes, background limits, clock/timezone/DST changes, retries, no duplicate send
    (due times are absolute epoch; DST/timezone resolution and restart/early-fire/no-duplicate are unit tested; clock,
    timezone and boot broadcasts re-queue pending sends via `OutboxClockReceiver`; real
    background-limit and clock-change behaviour still needs the device)
  - [x] Honest about Android not guaranteeing second-exact delivery; separate from the Undo-send grace period (`schedule_note`; `SendSchedule.MIN_LEAD_MS`)
  - [ ] End-to-end device verification
  - Evidence: presets + outbox due times exist (`WorkScheduling`); custom picker not implemented.

### Appearance

- **FR-55 · P2 · Fully rounded Quiet UI shapes** — pill/capsule controls, chips and grouped actions must have true
  semicircular ends; icon-only controls truly circular; dialogs/sheets consistently and generously rounded.
  - [x] Audit every pill/capsule/chip/floating control; one centralized shape token set (`DotShapes.pill/circle/dialog/
    sheet`; buttons, chips, recipient/quote chips and the floating bar use `pill`)
  - [ ] Matching clip/border/background in all states; Light/Dark/OLED, font scaling and animations checked on device
  - [ ] Primary actions stay solid electric blue; glass controls stay neutral and blurred
  - Evidence: `PillShapesTest` (token radii + rendered semicircular ends); device checks pending.

### Account and platform

- **FR-14 · P3 · Optional JMAP support** next to IMAP/SMTP for compatible providers, never mandatory. Planned after storage
  (Sol).
- **FR-44b · P3 · S/MIME** — user-owned certificate/key (PKCS#12 or Android KeyChain), sign/encrypt/both for new mail,
  replies and forwards, per-account defaults, per-recipient certificate validation, no silent downgrade, validity info,
  interoperability tests with real S/MIME clients, audited libraries only. Evidence: none; OpenPGP is FR-44a.

---

## In progress

### Accounts and sync

- **FR-01 · P1 · "Continue with Google" on the first Add account screen** — Google sign-in without IMAP settings or app
  passwords; system browser + PKCE; clear loading/cancel/error states; manual IMAP stays a separate option.
  - [x] Button, states, redirect handling, translated errors, honest message instead of a dead button when the build has
    no Google client ID (PR #57, merged; VM tests)
  - [ ] End-to-end with a real Gmail account on the phone (consent screen is in Testing mode: listed test users only,
    grants expire after 7 days)
- **FR-02 · P1 · OAuth2 sign-in and token refresh for Google and Microsoft, no Google Play Services** — PKCE, system
  browser, encrypted tokens, refresh, XOAUTH2 are implemented (Codex).
  - [x] Google client IDs registered, per-build-type (release/debug) client (`af45ecf`, `docs/OAUTH.md`)
  - [ ] Microsoft client ID not registered (maintainer decision, `docs/OAUTH.md`); Outlook/Hotmail cannot sign in meanwhile
  - [ ] Real-account verification (see FR-01); Google restricted-scope verification for a public release
- **FR-04 · P1 · Harden IMAP sync and message operations** — threading collisions, cross-folder archive/move, large-body
  storage, IDLE reconnect loops, global sync-lock contention, plus network-failure tests.
  - Forward closeout prepared: conservative threading, message-scoped SmartFolder actions, batched aggregation and
    bounded metadata/preview queries; regression and network tests in `docs/reports/codex-imap-hardening-closeout.md`.
    Awaiting forward PR review/merge. Lead-approved retroactive split/repair follows as a separate idempotent pass
    (oldest message retains the original thread ID; snooze/mute copied to split groups).
- **FR-05 · P1 · Real-device regressions: slow refresh, inconsistent HTML fit, connection errors**
  - [x] Refresh 18 s → ~3 s (Inbox ~1.6 s), logins 30 → 4 (PR #34), measured on device
  - [x] Connection errors classified and explained in EN/FR/DE with Retry (PR #34 + `de22513`)
  - [x] HTML fit verified on device (Infomaniak, Fnac, Recharge, AliExpress, GitHub) — see FR-20
  - [ ] The *intermittent* connection error was never reproduced or root-caused; keep until it stays away in daily use
- **FR-06 · P1 · Mirror drafts with the server Drafts folder** (Codex, PR #24) — revisions, offline retry, external-edit
  conflicts kept side by side. Follow-ups: trashed drafts stay local for the Undo window (`47dc95d`), encrypted drafts are
  never uploaded (OpenPGP, `777c498`). Device: pending (multi-device edit + conflict).
- **FR-07 · P1 · Opt-in instant notifications** (IMAP IDLE in a foreground service, off by default, battery trade-off
  explained) — PR #30. Device: physical Doze/battery test pending.
- **FR-08 · P1 · Configurable on-device mail download and storage limits, incl. a full-offline mode**
  - [x] Engine: per-account retention, payload caching, disk budgets, LRU eviction that never deletes server mail,
    resumable full-offline download, Wi-Fi/charging constraints, pause/resume, usage breakdown (PR #55, Room v9;
    `docs/reports/codex-storage-offline.md`)
  - [ ] Settings UI for policies, usage breakdown, offline progress, pause/resume (Sol implementation under review;
    EN/FR/DE, direct Settings search entry and Robolectric coverage; `docs/reports/codex-storage-settings-ui.md`)
  - [ ] Real-device checks (storage pressure, WorkManager constraints, large first download)
- **FR-12 · P2 · Native Android Contacts integration** — partly done: system contact picker needs no permission; contact
  photos are an opt-in avatar source; "From contacts" filter uses Sent/outbox correspondents or the permission-gated
  address book (PR #55). Gaps: recipient autocomplete uses mail history only, not the address book.
- **FR-13 · P3 · SimpleLogin / addy.io alias discovery and management** — PR #47 open (Luna); independent of either service.

### Reading and HTML mail

- **FR-25 · P2 · Swipe between conversations in the reader** (setting, off by default; ignores Back-gesture edges, pinch,
  vertical scroll; haptic tick). Evidence: implemented in v0.1. Device: pending.
- **FR-28 · P2 · Adjustable list density, reading typography, accessibility** — PR #45.
  - [x] Compact / comfortable / spacious row metrics; reader typeface (Inter/System/Serif) and line height (plain text via
    style, HTML via CSS variables); TalkBack custom actions on rows; 48 dp quote toggle; font scale 2× tested
  - [ ] Device: Inter loading in the WebView through the `dotfont://` scheme, TalkBack menu, spacious look
- **FR-29 · P1 · Persistent, configurable remote-image permissions** — per-message approval remembered by account +
  Message-ID, sender/domain allowlists with account scope, Settings → Privacy → Remote images, "Images loaded · Stop"
  (PR #6). Device: pending.
- **FR-30 · P2 · Anti-tracking and phishing protections**
  - [x] Link dialog: real host in Unicode, look-alike-domain warning, text↔href mismatch warning, tracking parameters
    removed by default (toggle), Copy/Share link stripped (PR #3, #32, #40); "N trackers blocked" note; email-open
    pixels are always filtered, even when images are allowed
  - [ ] **Settings → Privacy toggle for tracking-pixel blocking, independent of remote-image loading** (default on) —
    not implemented: the sanitizer always blocks pixels, there is no preference
  - [x] Settings → Remote images explains that remote images/pixels reveal when, roughly where and on which device a
    message was opened
- **FR-33 · P2 · Optional rules-based classification** (personal, newsletters, purchases, bills, notifications, social):
  classifier in sync, Newsletters/Purchases/Bills/Notifications/Social views, Settings page, user rules re-applied to
  cached mail, no external processing, nothing hidden from the Inbox (PR #8, #27, #39). Device: pending.
- **FR-34 · P2 · Calendar invitations** — cards with Add to calendar and Accept/Maybe/Decline (iTIP reply from the invited
  identity through the outbox, 5 s undo) (PR #1, #10, #26). Device: pending; Dot Calendar integration is future work.
- **FR-35 · P2 · Attachment browser across accounts** (PR #22, #49) — done: type filters, search by name/sender, month
  groups, PDF preview, share. Gaps: date and sender filters (only search/grouping), attachment-content search in the
  browser (PDF text is indexed for mail search, FR-67), export.
- **FR-45 · P2 · Distinct signed / encrypted badges (OpenPGP now, S/MIME later)** — the reader shows one status line
  (encrypted, decrypted, signed by X verified / unconfirmed / key missing / expired / invalid; install link without
  OpenKeychain), commit `4fd674d`.
  - [ ] Signed **and** encrypted should show two distinct indicators (today one combined line)
  - [ ] "Unsupported encryption" state and an explanation on tap; indicators in list/search where unambiguous
  - [ ] S/MIME (FR-44b); light/dark consistency check on device

### Composing

- **FR-40 · P1 · Per-account and per-identity HTML signatures** (PR #36, #40) — done: rich editor (bold/italic/underline/
  links/lists), sanitized Import HTML, inline logo as `cid:` in multipart/related, new vs reply defaults, no duplicates,
  per-message remove/change, survives drafts, plain-text fallback; editor checked on device.
  - [ ] Verify the sent mail in real clients (Gmail, Apple Mail, Thunderbird): inline logo, formatting, plain-text part
- **FR-44a · P3 · OpenPGP sign/encrypt via OpenKeychain** (key management is OpenKeychain's, by design) — PR #50 +
  `777c498`: Sign/Encrypt chips (only with OpenKeychain), per-account defaults, key required for every recipient,
  RFC 3156 builder with engine round-trip tests, protected MIME stored in the outbox (fail-closed), encrypted drafts never
  uploaded. Gaps: no global default (per-account only), no per-identity default, no protected headers (Subject visible),
  no key import/export UI of our own, no interoperability test with real clients. Device: pending (real OpenKeychain).

### Appearance and UI

- **FR-50 · P1 · Electric-blue accent instead of lavender** — PR #56 open (tokens, primary-action fill, launcher mark,
  contrast tests). Dark-mode accent text/link colour was `#7C9CFF` (pale) before it; owner reviews visually; before/after
  screenshots on device required before Done.
- **FR-51 · P2 · OLED / Pure Black theme** — PR #52 merged: `#000000` background, lifted surfaces/hairlines, window and
  nav-bar handling, AA-contrast and pixel tests.
  - [ ] Device validation in all four modes; known limits: one charcoal frame at cold start (static starting window),
    WebView algorithmic darkening leaves dark grey for mails with their own white background
- **FR-53 · P1 · Glass controls must be real, translucent, blurred** (reopened after the owner saw opaque controls) —
  Backdrop blur now runs on liquid-glass (PR #70, Haze removed) for the Compose pill, reader capsules and snackbars.
  - [ ] Audit every intended glass control in light/dark/OLED on device: background perceptible and blurred live while
    scrolling, neutral graphite tint (no blue/purple), no solid fallback where blur is supported
  - [ ] Primary blue buttons and flat secondary controls stay non-glass
- **FR-54 · P1 · Top-edge scroll blur/fade** (separate from glass capsules) — PR #70 merged (supersedes #54): frosted
  header on liquid-glass, band bleeding off the screen edge, opaque page-colour gradient from the edge, one shared blur
  radius. Owner-reported bugs:
  - [x] Blur flickers/flashes while scrolling — owner accepted on device 2026-11-09 ("good enough")
  - [ ] Band height/position does not follow the inbox filter row when it collapses/expands (must track the real header
    bounds)
  - [ ] No separate dark fade before the blur (content goes sharp → progressively blurred, no dark strip or seam)
  - [ ] Slow/fast/reverse scroll, filter changes, light/dark/OLED, WebView redraw artefacts, GPU cost
- **FR-56 · P1 · Full-app Quiet UI visual audit (light + dark)** — PR #35, #48, #49 reviewed Demo renders of categories,
  smart folders, search, composer, account setup, attachments, outbox; fixes applied.
  - [ ] Not auditable on the JVM: snooze sheet, dialogs, menus, bottom sheets → device pass
  - [x] Remaining Material remnants: the three `CircularProgressIndicator`s (search loading, alias picker loading and
    per-alias row) are now `DotSpinner`; the reader's tracking-strip `Checkbox` is the new `DotCheckbox`. A source guard
    (`QuietRemnantsTest`) fails if a Material progress indicator or check box returns.
  - [ ] Before/after screenshots (device/Demo) · intended exceptions, kept on purpose as Material behaviour re-skinned
    with Quiet tokens: `AlertDialog`, `ModalBottomSheet`, `DropdownMenu` (inside `DotMenu`), `DatePicker`/`TimePicker`
    (+ `DatePickerDialog`), `SwipeToDismissBox` (inbox swipe), `ModalNavigationDrawer`, `SnackbarHost` (content is
    `DotSnackbar`), `Switch` (via `DotSwitch`) and `Slider` (settings), `Icon`/`Text` primitives.
- **FR-58 · P2 · Polish animations across meaningful actions** — done: screen transitions, selection mode, menus/dialogs,
  Cc/Bcc reveal, reduced-motion lists, Outbox removal, reader capsules (PR #33), shared haptics (PR #37), folder-switch
  ease-in, search open/close, expand/collapse polish, swipe reveal (PR #46). Device: feel/timing pending; reduced-motion
  on device pending.
- **FR-65 · P2 · Full localization (FR/DE), plurals, RTL, locale formats** — all screens EN/FR/DE with plurals, verify/OAuth
  errors, recurrence text, locale-aware sizes, auto-mirrored icons, Compose RTL tests (PR #28, #43).
  - [ ] Data-layer fallback texts still English when no connection kind is known ("Folder unavailable", sync fallbacks,
    some `MailSyncException`/`MailActionException` messages)
  - [ ] Numbered-list editor icon has no mirrored variant; RTL never verified on a device in an RTL locale (swipe
    directions, drawer, pinch)
- **FR-68 · P1 · Search must not show "No matches" while still searching** — PR #53 (explicit idle/searching/results/
  confirmed-empty/partial/error states, no stale results on edits). Device: pending, with queries that take seconds.
- **FR-69 · P2 · Adaptive tablet/foldable layouts and keyboard shortcuts** — done: two-pane list + conversation from 840 dp.
  Shortcuts: inbox `c`, `/`; reader `e`, `#`/Delete, `u`, `s`, `j`/`k`. Gaps: list navigation/open/select shortcuts in the
  inbox, foldable posture handling, device check on a tablet/DeX.
- **FR-72 · P1 · Properly signed release APKs and real-device validation before public distribution** — v0.1.0-alpha.1
  published, `scripts/reprotest.sh` shows byte-identical builds, `release.sh` builds from a clean clone
  (`docs/RELEASES.md`). Gap: end-to-end validation on the real device of the release build.
- **FR-75 · P1 · Housekeeping and full Done audit of this file** — this rewrite (this PR). Next audits: FR-76.

---

## Done

Each entry: evidence in brackets. "Device ✓" = checked on the owner's phone and recorded in the PR or commit.

### Mail core
- **FR-03 · P1 · Secure TLS in discovery and verification** — no silent plaintext fallback; plaintext-only providers need
  explicit confirmation; TLS 1.2+ with certificate-failure reporting [PR #2, #17; regression tests].
- **FR-09 · P1 · IMAP folder management** — create, rename, nest, delete; queued offline ops, server-wins reconciliation
  [PR #4; replay verified live on Infomaniak].
- **FR-10 · P2 · "Sign in again" after a password change** — banner or Settings → account; both servers verified before saving
  [PR #19, #21].
- **FR-36 · P2 · Conversation view toggle** — Settings → "Group into conversations"; per-message list keeps all actions,
  sort and filters [PR #13, `66269da`].
- **FR-26 · P2 · Post-action navigation** — after archive/delete go to next, previous or the list [Settings → General].
- **FR-70 · P2 · Notification rules** — mute conversations, per-folder notify, choose notification buttons [PR #23].
- **FR-71 · P2 · Settings backup/restore without credentials** — JSON via the system picker, preview before import; includes
  preferences, remote-image rules, templates, smart folders [PR #25, #41]. Not in the file yet: category rules.

### Reading
- **FR-20 · P1 · HTML viewport fit and rendering parity** — sanitized stylesheets and `@media` kept, fixed-width designs
  scaled to fit, leftover wide widths in responsive mails made fluid, remote content still opt-in, pixels never load
  [`a861cdc`, `1a16c3d`; Device ✓: Infomaniak device-update mail, Fnac, Recharge, AliExpress, GitHub vs Samsung Email/Gmail].
- **FR-23 · P2 · Pinch-to-zoom for HTML mail** — 1–3×, layout grows so scrolling reaches the end, sideways pan, links
  unaffected [PR `25be49f`, `4845905`; Device ✓ scroll/links]. Not done (the request said "if feasible/appropriate"):
  double-tap zoom/reset, a Settings toggle.
- **FR-24 · P2 · Reader floating actions** — Back bubble + grouped top capsule + grouped reply capsule, hide on scroll,
  status-bar scrim [PR #33, #35; Device ✓].
- **FR-31 · P2 · PDF/document preview and share** — in-app preview with pinch zoom, share to any app (Paperless-ngx), local
  text view (Android 15+), PDF text feeds the search index [`ac9a0b9`; Device ✓].
- **FR-32 · P2 · Newsletter view with safe unsubscribe** — RFC 2369/8058 one-click with confirmation [PR #5, #27].

### Search, lists and sync feedback
- **FR-67 · P1 · Full-content search** — FTS4 index of bodies, attachment names and PDF text; operators; scoped search;
  bounded server fallback; highlighted results, scope chips, server progress, "On server" tags [PR #42, `41b23d4`;
  Device ✓ with a body-only word].
- **FR-64 · P2 · Search within Settings** — titles, descriptions, translated synonyms, accounts, jumps to the control
  [`c2130d2`; Device ✓].
- **FR-66 · P2 · Sorting, quick filters, smart folders** — 4 sorts; Unread/Starred/Attachments/To me/From contacts chips;
  smart folders in the drawer with Settings page and backup [PR #41, `66269da`; Device ✓].
- **FR-60 · P1 · Pull-to-refresh polish** — spinner leaves after ≤0.9 s, 2 dp header progress follows real sync, threshold
  haptic [Device ✓ 2026-11-08].
- **FR-61 · P2 · "Synced HH:mm" and success checkmark** — persisted, only advanced by clean syncs [`8f187da`; Device ✓].
- **FR-62 · P2 · Message-list dates** pinned to the right edge, 24-hour follows the system [v0.1].

### Compose and organisation
- **FR-41 · P2 · Rich-text email** with a plain-text option and default; multipart/alternative; drafts keep formatting
  [PR #2, #7].
- **FR-42 · P2 · Email templates** — Compose → ⋯ → Templates with `{{first_name}}`, `{{name}}`, `{{my_name}}`, `{{date}}`
  [PR #9].
- **FR-43 · P2 · Snooze / remind later** — presets and date/time, Snoozed list, resurfaces with a notification
  [PR #9, #18, `fc1c78d`].

### Appearance
- **FR-52 · P2 · Quiet UI button hierarchy** — solid accent-blue primaries, frosted floating pill and snackbars, flat
  secondaries [PR #7, #32; Device ✓].
- **FR-59 · P2 · Sender avatars and optional pictures** — colourful avatars; opt-in contact photos, Gravatar, Libravatar,
  BIMI brand logos, website icons; cached, no WebView [`0743bda`, `701d7b3`, PR #36 for inset logos].
- **FR-63 · P2 · Complete Settings screen** — general, appearance, reading, composing, privacy, storage, about; per-account
  sync options [PR #14, v0.1].

### Project
- **FR-73 · P1 · First signed release APK** — v0.1.0-alpha.1 on GitHub Releases with certificate fingerprint and SHA-256.
- **FR-74 · P2 · README, docs and regular commits** [v0.1].

---

## Process

- **FR-76 · P2 · Recurring backlog and regression review (ongoing)** — reassess Open / In progress / Done after substantial
  merges, before each alpha/release and after bug reports: compare with the code and real behaviour, update priorities and
  acceptance criteria, find duplicates, conflicts and regressions, re-test affected Done features and reopen with exact gaps.
  Never "finished" after one cleanup.

## Needs a device check (collected from the entries above)

FR-01 real Gmail end to end · FR-06 multi-device drafts · FR-07 Doze/battery · FR-08 storage UI + WorkManager · FR-21 flicker ·
FR-22 Back crash · FR-25 reader swipe · FR-28 Inter in WebView + TalkBack · FR-29 remote images · FR-33 categories UI ·
FR-34 invitations · FR-40 sent signatures in real clients · FR-44a real OpenKeychain round trips · FR-50 accent (before/after) ·
FR-51 OLED in all modes · FR-53 glass blur audit · FR-54 top-edge blur (3 reported bugs) · FR-56 snooze sheet/dialogs ·
FR-58 motion feel + reduced motion · FR-65 RTL locale · FR-68 slow-search states · FR-69 tablet/DeX · FR-72 release build.
