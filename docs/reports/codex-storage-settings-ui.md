# Storage Settings UI — Sol

Implements the Settings-facing part of FR-08 using the Room v9 APIs merged in PR #55. No engine/data schema, dependencies, blur/glass code, or device operations are included.

## Entry points and behavior

Settings → Storage → **Mail storage and offline** opens the page. The Settings search index has a direct `SettingsTarget.Storage` entry, with translated offline/download/cache/retention/quota/charging/network keywords. Back closes this subpage before leaving Settings; the active page/account and editor fields survive recreation.

The page shows physical total/SQLite/search/outbox/draft/temporary usage and available device space, plus per-account payload/protected usage. Refresh usage is explicit. The shared global cap has unlimited and 128 MiB/512 MiB/1 GiB/5 GiB choices; any existing custom value remains selectable. Protected data can exceed a cap.

Per-account policy editing supports latest-N, last-X-days or all messages; a numeric payload cap in MiB (0 means unlimited); body/attachment download; Wi-Fi-only and charging-only. **Use unlimited full offline** prepares ALL + body downloads + no account payload ceiling, preserving the attachment/network/charging choices. It does not start a download until **Apply policy**, so the user reviews the settings first. Global limits and the mandatory device reserve remain effective. Explicitly enabling attachments also enables bodies; disabling bodies disables attachment prefetch.

Progress states are localized: queued/downloading/paused/network/charging waits/storage limit/device full/complete/partial/error. Header/body/attachment counts come from the repository; an unknown total is not shown as a fake completed fraction. Pause, resume and retry dispatch repository operations. Clear downloaded mail requires confirmation, freezes the target account, pauses prefetch and preserves headers/protected local work/server messages. The result reports freed bytes. The older temporary-cache action remains separate.

All repository IO runs in StorageViewModel on Dispatchers.IO with serialized actions. Composition owns only presentation/edit state and dispatches commands. Errors produce a localized retryable message. Numeric validation rejects invalid ranges and Long-byte conversion overflow. Editor state has an explicit primitive list Saver.

## Validation

Seven focused Robolectric/Compose tests cover numeric bounds/overflow, editor restoration, account-targeted apply, delayed full-offline activation, attachment/body coupling, confirmation/cancel/protected clear semantics, and pause/resume progress. Tests use an in-memory graph and never access the phone or real mailbox. Full app/mail/data tests, lint and debug/R8 release build results are recorded in the PR after final validation.

EN/FR/DE resources are in separate storage_strings.xml files, one resource per line. The shared public data API is unchanged. No tests were run on the phone: Claude is handling device validation and the shared frosted-header rollout. This page currently uses the DotTopBar API on origin/main; only this page should adopt Claude's wrapper once his header API merges, without changing blur/glass internals.

FR-08 stays In progress pending merge and real-device storage pressure, WorkManager constraints and large-download checks. No user requirement is marked device-verified by these JVM tests.
