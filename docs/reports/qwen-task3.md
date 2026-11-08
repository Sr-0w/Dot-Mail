# Qwen — TASK C: unit tests for pure logic in :app and :designsystem

Status: **done.** `scripts/gw :app:testDebugUnitTest :designsystem:testDebugUnitTest` →
**63 tests, 59 passing, 4 failing — all 4 failures are intentional bug-reporting tests**
(named `BUG …`). No production code was modified, per instruction.

## Test files (all under `app/src/test/kotlin/app/dot/mail/` — the only allowed location)

| File | Target | Tests | Result |
|---|---|---|---|
| `MailtoParserTest.kt` | `MailtoParser` (MainActivity.kt) | 25 | 24 pass, 1 BUG fail |
| `ComposeAddressTest.kt` | `ComposeViewModel.parseAddress` / `isValidEmail` companion | 21 | 19 pass, 2 BUG fail |
| `InitialsTest.kt` | `initials()` (designsystem `components/Basics.kt`) | 17 | 16 pass, 1 BUG fail |

All tests are pure JVM (JUnit4 `org.junit.Assert` — **Truth is not actually declared in
`:app`'s testImplementation**, contrary to the task note; only `testImplementation(libs.junit)` is
there). No `android.*` touched, no Robolectric. `:designsystem` has no test dependencies at all, so
its `initials()` test is hosted in `:app` (the module already depends on `:designsystem`).

## Covered behaviour (passing)

**MailtoParser / RFC 6068** — single + multiple recipients (comma, semicolon), `to`/`cc`/`bcc`
query params (multi-value, appended after the address part), percent-encoded subject/body
(incl. `%0A` newline, UTF-8 `%C3%A9`), `+` kept literal in address part and query
(`subject=A+B` → `A+B`, `%2B` → `+`, `%20` → space), case-insensitive keys
(`SUBJECT=`/`CC=`/`BODY=`), `=` inside values, `%26` inside subject, unknown params ignored,
empty `mailto:`, missing scheme, trailing `?`, malformed percent-encoding fallback.
`MAILTO:` uppercase scheme passes (explicitly handled by the code).

**parseAddress** — plain, `Name <addr>`, quoted name, name containing `< >`, bare
`<addr>`, whitespace trimming, stray single brackets. Plus `EmailAddress.displayName`
fallback (name / blank name / no name).

**isValidEmail (accepted)** — typical addresses, plus/dot in local part, unicode local part,
punycode domain, trimming, 2+ char TLD, rejection of space/`<>`/empty parts/`@@`/no-dot.

**initials** — first+last (incl. 3-word), uppercase mapping, single word/letter, dot/hyphen/
underscore separators, `email@x` → local part, empty/whitespace/punctuation → `·`, digits kept,
Cyrillic/Scandinavian letters, apostrophe splitting.

## 4 real bugs found (failing tests, production code untouched)

### 1. `MailtoParserTest > BUG mixed-case scheme Mailto must be parsed`
`MailtoParser.parse("Mailto:a@example.com?subject=Hi")`
→ actual: `to = ["Mailto:a@example.com"]` · expected: `to = ["a@example.com"]`.
The code only strips the prefixes `"mailto:"` and `"MAILTO:"`; any mixed case (`Mailto:`,
`MaIlTo:` — RFC 3986 §3.1 says schemes are case-insensitive, and web mailto-links do use
mixed case in the wild) leaves the scheme glued to the first recipient, producing an
unsendable address.
Suggested fix: case-insensitive prefix strip, e.g.
`val raw = mailto.replaceFirst(Regex("^mailto:", RegexOption.IGNORE_CASE), "")`.

### 2. `ComposeAddressTest > BUG consecutive dots in domain must be rejected`
`ComposeViewModel.isValidEmail("a@b..co")` → actual `true`, expected `false`
(empty label — will fail at SMTP/IMAP time with a confusing error instead of inline validation).

### 3. `ComposeAddressTest > BUG trailing dot in domain must be rejected`
`ComposeViewModel.isValidEmail("a@b.co.")` → actual `true`, expected `false`.
Same root cause: `Regex("^[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]{2,}$")` allows dots inside the
final `{2,}` run. Suggested fix for 2+3: reject domains containing `..` or ending with `.`,
or tighten the domain part to `(?:[A-Za-z0-9-]+\\.)*[A-Za-z0-9]{2,}`.

### 4. `InitialsTest > BUG display name with embedded address`
`initials("Alice Smith <a@b.co>")` → actual `"AA"`, expected `"AS"`.
The `substringBefore('@')` heuristic keeps the local-part character `a` from `<a@b.co>` as a
trailing "word". No current caller feeds such input (callers use `EmailAddress.displayName`,
which never contains brackets — verified in InboxScreen/MailDrawer/ThreadScreen/ComposeScreen/
SettingsScreen), but `initials` is a public design-system utility that explicitly special-cases
`@`, so its own contract is violated for the one input shape it claims to handle.
Suggested fix: strip the bracketed part first:
`val cleaned = name.substringBefore('<').substringBefore('@').replace(...)`.

## Not verified / caveats

- Bug 4 is defensive (no live caller currently hits it) — flagged for completeness, low severity.
- Bugs 1–3 are reachable from real user input (typed addresses / third-party mailto links).
- I did not test `MainActivity.parse()` / `mailto()` (Android `Intent`-bound); only the pure
  `MailtoParser` and the ViewModel companion objects, as scoped.
