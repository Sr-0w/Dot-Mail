# Qwen — Task 1: account autodiscovery (`engine/autoconfig`) + text helpers (`engine/text`)

Status: **done, all green.** `scripts/gw :mail:test --tests 'app.dot.mail.engine.autoconfig.*' --tests 'app.dot.mail.engine.text.*'` → **88 tests, 0 failures** (run 2026-11-08 ~03:49, report in `build-out/reports/mail/tests/test/`).

## Files (only inside my two owned dirs)

```
mail/src/main/kotlin/app/dot/mail/engine/autoconfig/
  Types.kt            SocketType, Source, DiscoveredServer, DiscoveredConfig (exact API from Claude)
  ConfigFetcher.kt    ConfigFetcher (fun interface) + HttpsConfigFetcher
  BuiltInProviders.kt internal provider table, domain → entry
  AutoconfigXml.kt    XXE-safe Thunderbird autoconfig / ISPDB XML parser
  AutoDiscovery.kt    email parsing/normalisation + 4-step discovery on Dispatchers.IO
mail/src/test/kotlin/app/dot/mail/engine/autoconfig/
  AutoDiscoveryTest.kt (29)  AutoconfigXmlTest.kt (14)  HttpsConfigFetcherTest.kt (10)
  mail/src/test/resources/autoconfig/*.xml (9 fixtures: simple, multiple-variants, pop3-only,
  placeholders, ispdb-posteo, xxe, malformed, no-servers, missing-port)
mail/src/main/kotlin/app/dot/mail/engine/text/
  QuoteSplitter.kt    QuoteSplit + QuoteSplitter (exact API)
  Snippets.kt         Snippets.fromPlainText / fromHtml
mail/src/test/kotlin/app/dot/mail/engine/text/
  QuoteSplitterTest.kt (17)  SnippetsTest.kt (18)
```

## TASK A — autodiscovery

**Order:** built-in table → `https://autoconfig.<domain>/mail/config-v1.1.xml` →
`https://<domain>/.well-known/autoconfig/mail/config-v1.1.xml` →
`https://autoconfig.thunderbird.net/v1.1/<domain>` → guess `imap.<domain>:993 SSL` / `smtp.<domain>:465 SSL`.
Only the domain ever appears in URLs (test asserts no `@`/local part leaks). A source that returns
HTTP 200 but an unusable document falls through to the next source.

**Parser rules** (Thunderbird autoconfig v1.1, same schema for provider autoconfig and ISPDB):
- incoming: prefer `type=imap`; `pop3` (or untyped) only when no imap block exists;
- socket: prefer SSL > STARTTLS > PLAIN; missing port → default per socket type (993/143, 465/587/25);
- substitutes `%EMAILADDRESS%`, `%EMAILLOCALPART%`, `%EMAILDOMAIN%` in host and username;
- missing username → full email; missing `<port>` → default; port outside 1–65535 → block rejected;
- both IMAP and SMTP must be resolvable, else the whole document is rejected (fall through).
- **XXE-safe:** `disallow-doctype-decl`, no external entities, secure-processing; any DOCTYPE (including
  `SYSTEM "file:///etc/passwd"` and remote DTDs) makes the document unusable. Tested with fixtures.

**HttpsConfigFetcher:** HTTPS-only (other schemes rejected pre-I/O), 8 s connect + 8 s read, manual
redirects (≤5 hops, https only, relative locations resolved), no cookies (JDK default, never installs a
CookieHandler), bounded read via explicit buffer capped at 256 KiB + 1 — never waits for EOF, oversized
→ null. Hermetically tested with an injected stub `HttpURLConnection` (the constructor is `internal` for
testability; public API is exactly `HttpsConfigFetcher()`).

**Built-in table — verification.** Claude flagged several wrong hosts in my first draft; I re-verified
*every* entry against the Mozilla ISPDB (fetched live today) and/or provider docs before accepting:

| Provider | IMAP | SMTP | Notes / source |
|---|---|---|---|
| Gmail (+googlemail .com/.de/.co.uk) | imap.gmail.com:993 SSL | smtp.gmail.com:465 SSL | ISPDB ✓; app-password hint |
| Outlook/Hotmail/Live/MSN (60+ domains per ISPDB) | outlook.office365.com:993 SSL | smtp-mail.outlook.com:**587 STARTTLS** | ISPDB ✓ (465 not listed — my earlier 465 was wrong, fixed per Claude); **oauthRequired=true** (ISPDB marks both servers OAuth2) |
| Yahoo (17 domains per ISPDB incl. ymail/rocketmail/cox.net) | imap.mail.yahoo.com:993 SSL | smtp.mail.yahoo.com:465 SSL | ISPDB ✓; app-password hint |
| iCloud (icloud/me/mac.com) | imap.mail.me.com:993 SSL | smtp.mail.me.com:465 SSL | Apple HT202304 ✓ (465 SSL is Apple's first-listed option); app-specific-password hint |
| Infomaniak (infomaniak/ik.me/ikmail/etik/ik.io) | **mail.infomaniak.com**:993 SSL | **mail.infomaniak.com**:465 SSL | official FAQ 468 ✓ (my earlier imap./smtp. subdomains were wrong, fixed per Claude); 465 SSL is the documented "option 2" |
| Fastmail (6 domains) | **imap.fastmail.com**:993 SSL | smtp.fastmail.com:465 SSL | Fastmail's own autoconfig ✓ (my earlier mail.fastmail.com was wrong) |
| GMX .net/.de | imap.gmx.net:993 SSL | **mail.gmx.net**:465 SSL | ISPDB ✓ (smtp.gmx.net was wrong, fixed per Claude). ISPDB caveat: IMAP is "officially paid, but works with freemail too" |
| GMX .com | **imap.gmx.com**:993 SSL | **mail.gmx.com**:465 SSL | ISPDB ✓ (separate US servers, fixed per Claude) |
| web.de | imap.web.de:993 SSL | smtp.web.de:465 SSL | ISPDB ✓; **username = local part only** (per ISPDB) — handled via `usernameIsFullEmail=false` |
| mailbox.org | **imap.mailbox.org**:993 SSL | smtp.mailbox.org:465 SSL | mailbox.org's own autoconfig ✓ (my earlier mail.mailbox.org was wrong) |
| Posteo (44 domains per ISPDB) | **posteo.de**:993 SSL | **posteo.de**:465 SSL | ISPDB ✓ (my earlier emailrelay.org host was wrong, fixed per Claude) |
| Zoho .com/.zohomail.com | imap.zoho.com:993 SSL | smtp.zoho.com:465 SSL | ISPDB ✓ (my earlier imap.zoho.eu for zoho.com was wrong) |
| Zoho .eu/.zohomail.eu | imap.zoho.eu:993 SSL | smtp.zoho.eu:465 SSL | ISPDB ✓ |
| AOL (18 domains per ISPDB incl. aim/netscape/compuserve) | imap.aol.com:993 SSL | smtp.aol.com:465 SSL | ISPDB ✓ |
| Proton (proton.me/protonmail.com/pm.me) | mail.proton.me (nominal) | mail.proton.me (nominal) | **see design note below** |

Unlisted domains still work via the fallback chain (autoconfig/ISPDB/guess) — deliberate, per Claude's
"only list hosts you are certain of".

**Proton design (per Claude's direction):** Proton Mail Bridge is desktop-only (Windows/macOS/Linux;
verified on proton.me — Bridge is listed under desktop apps, and Proton's Android support page states
third-party clients are not supported on Android), so a `127.0.0.1` config can never work on Android.
We therefore return a built-in config with **`oauthRequired = true`** (the flag meaning "password
sign-in will not work") and hint *"Proton Mail doesn't offer IMAP access on Android (Proton Mail Bridge
is desktop-only). Use the Proton Mail app instead."* Hosts are nominal (`mail.proton.me`, the domain's
MX) since the UI should short-circuit on the flag + hint before connecting.

**Email normalisation:** trim, split at last `@`, reject blank parts / whitespace / extra `@` / bad
domain shape (label regex), lowercase domain, `java.net.IDN.toASCII` (punycode); the resolved username
uses the ASCII domain (e.g. `jürgen@münch.de` → `jürgen@xn--mnch-0ra.de`).

## TASK B — text helpers

**QuoteSplitter** — finds the earliest quote-start marker: `^>` (any nesting depth),
`On … wrote:` / `Le … a écrit :` / `Am … schrieb …:` (case-insensitive, colon optional), or
`-----Original Message-----`. Quoted tail = marker line → end of text; visible = everything before it
(trimmed end) — **signatures (`-- `) stay visible** because they precede the marker.
Never hides everything: if only whitespace precedes the quote (whole message quoted, or writing
interleaved after it), `visible = text`, `quoted = null`. Handles `\n`, `\r\n`, `\r`.
Heuristic caveat: a plain line that happens to start with `On … wrote:` would be treated as a header
— accepted risk, same behaviour as most clients.

**Snippets** — `fromPlainText`: drop quoted tail → collapse whitespace (incl. NBSP) → clip to `max`
with `…`. `fromHtml`: jsoup parse, strip `head/style/script/link/meta/title/object/embed/iframe`, drop
hidden elements (`display:none`, `visibility:hidden`, `mso-hide`, `font-size:0`, `line-height:0`) and
preheader-ish classes, extract text with a DOM walker that maps block tags to newlines (so quote
detection works) and skips whitespace-only text nodes (markup indentation), then same drop/clip.
**Link policy:** anchor *text* is kept, hrefs never appear (jsoup text extraction). Decision point for
Claude if you'd rather drop URL-looking anchor text.

## What I did NOT verify (honest list)

- No live network test of `HttpsConfigFetcher` against real autoconfig servers (tests are hermetic per
  team rules); its logic (scheme/redirect/size/timeout) is stub-tested, but e.g. real TLS behaviour is
  not covered here.
- Built-in host/port pairs are verified against ISPDB/provider docs, not by dialing the servers. The
  GMX IMAP "officially paid" caveat above applies.
- `oauthRequired=true` for Proton relies on the UI treating that flag as "stop, show hint"; if Codex's
  account-setup flow instead tries an OAuth2 dance for it, we need a dedicated flag — flagging now.

## Coordination notes

- Fixed (from Codex): `readBytes(limit)` error-deprecated in Kotlin 2.1 → explicit bounded buffer loop;
  `node.text` → `node.text()`; JDK 21 removed several `HttpURLConnection` members my test stub
  overrode → stub now matches the real JDK 17/21 surface; `java.util.ArrayDeque` → Kotlin `ArrayDeque`;
  `suspend discover` called from non-suspend lambdas → `for` loops / `suspend fun`.
- No new dependencies (coroutines, jsoup, JUnit4, Truth, coroutines-test — all pre-declared).
- No build files touched, no commits made, stayed inside my two packages.
