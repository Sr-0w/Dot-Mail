# Dot Mail — team working agreement

Product brief: `PROJECT_BRIEF.md`. Lead: Claude (terminal t1). Read this once; keep it short.

## Architecture (4 Gradle modules)

| Module | What | Owner |
|---|---|---|
| `:mail` | Pure-JVM mail engine: IMAP/SMTP/MIME on Jakarta Mail (Eclipse Angus), HTML sanitiser. No Android imports. Tested on the JVM against GreenMail. | **Codex** (except `engine/autoconfig/` and `engine/text/` → **Qwen**) |
| `:data` | Room DB, repositories implementing `data/.../Repositories.kt`, WorkManager sync, IMAP IDLE, outbox/undo-send, notifications, Keystore credential storage. | **Codex** |
| `:designsystem` | "Quiet" design system: tokens, typography, components. Shared by future Dot apps. | **Claude** |
| `:app` | Compose UI, navigation, ViewModels, MainActivity, manifest. | **Claude** |

**Contract**: `data/src/main/kotlin/app/dot/mail/data/model/Models.kt` and `data/.../Repositories.kt`.
The UI only depends on those interfaces + `MailGraph`. Codex may change them, but must message Claude (`hc send t1 ...`) with what changed.

Package roots: `app.dot.mail.engine` (:mail), `app.dot.mail.data` (:data), `app.dot.design` (:designsystem), `app.dot.mail` (:app).

No DI framework: `:data` exposes `fun createMailGraph(context: Context): MailGraph`; `:app` calls it once in `DotMailApp.onCreate`.

## Rules

- **Builds**: always `scripts/gw <tasks>` (e.g. `scripts/gw :mail:test`). It syncs the tree to the build host
  (192.168.0.232, 12 cores / 88 GB) and runs Gradle there, one build at a time. Never run `./gradlew` directly
  on this laptop (7 GB RAM, shared by three agents). Reports come back in `build-out/reports/<module>/`.
- Stay inside the files you own. Need a change elsewhere? Message the owner.
- Don't run `git commit`; Claude integrates and commits.
- No new dependency without a FOSS licence compatible with GPL-3.0-or-later; no Google Play Services, Firebase,
  analytics or crash reporters. Note new deps in your report.
- Never send real user mail or credentials to any external service, including AI APIs. Tests use fixtures and GreenMail.
- "Done" means compiled and tested via `scripts/gw`. Say what you did not verify.
- Report with `hc send t1 "<summary; details in path>"` when done or stuck. Put long reports in `docs/reports/<you>-<topic>.md`.
- Disagree when you think a decision is wrong — say so with reasons. Better now than after integration.

## Key decisions (challenge them if you see a problem)

- Licence GPL-3.0-or-later. minSdk 26, target/compile 35. Kotlin 2.1, AGP 8.7, Compose BOM 2024.12.
- Offline-first: UI reads Room via Flow/Paging; writes are optimistic + queued.
- Sync: WorkManager periodic (15 min, network-constrained) + IDLE on INBOX only while the app is in the
  foreground. No permanent foreground service by default (battery). Expedited one-shot sync on pull-to-refresh.
- Security: passwords/tokens encrypted with an AES-GCM key held in Android Keystore. HTML rendered in a WebView with
  JS off, no network (every request intercepted; remote images blocked by default), after jsoup sanitising.
- Auth v0.1: password / app password. OAuth2 (Gmail, Microsoft) is planned; keep `AuthType.OAUTH2` paths in mind.

## Git / GitHub

Repo: https://github.com/Sr-0w/Dot-Mail (public, GPL-3.0-or-later). One shared working tree; Claude commits
at integration points with clear messages crediting the author agent. For larger changes, an agent may work on a
branch and open a PR (`gh pr create`) for review by another agent — never force-push `main`, never commit secrets
(scan for passwords before any commit; test credentials live only in ~/.config/dotmail/).
