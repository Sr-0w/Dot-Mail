# Mail engine implementation

Owner: Codex. No commits; :app/:designsystem untouched.

## Compatibility decision

Angus explicitly supports standard distribution on Android API19+, below our API26 floor:
https://eclipse-ee4j.github.io/angus-mail/Android

Use Angus Mail **2.0.5**, agreed with Claude, rather than 2.0.3: release 2.0.4 fixes CVE-2025-7962:
https://github.com/eclipse-ee4j/angus-mail/releases

Explicit dependencies: org.eclipse.angus:angus-activation:2.0.3 and jakarta.activation:jakarta.activation-api:2.1.4, alongside jakarta.mail-api:2.1.3. The Activation runtime supplies registry providers missing from the API-only jar. MIME construction supplies an explicit per-DataHandler MailcapCommandMap for Angus handlers, avoiding dependence on Android desktop activation classes or a global mutable command map. No javax.mail/activation artifacts. Built-in XOAUTH2 does not depend on unsupported SASL. API26-compatible stream loops; no InputStream.readNBytes.

Angus/Activation: EPL-2.0 or GPL-2.0 with Classpath exception (upstream dual license); jsoup MIT; existing test GreenMail Apache-2.0. No proprietary service dependency.

## Public surface

app.dot.mail.engine.MailClient, Endpoint/Credentials, Header/SyncCursor/HeaderSync, Mime, OutgoingMail, ParsedBody, Threading, MailSearch.

UI sanitizer: app.dot.mail.engine.html.HtmlSanitizer.sanitize(html, allowRemoteImages=false, cidUris=emptyMap()): SanitizedHtml(html, blockedRemoteResources), also hasRemoteImages getter. Root engine sanitizer additionally returns detection independent of whether resources were allowed.

## Behavior

* SSL/TLS and required STARTTLS, hostname verification, connect/read/write timeouts, built-in XOAUTH2 option.
* Folder roles use SPECIAL-USE attributes first, then Gmail/Infomaniak/common English/French names.
* Initial header window: last 30 days, at most 500 messages. Incremental UID pages do not advance past a truncated page. UIDVALIDITY resets, UIDNEXT checkpoints, CONDSTORE flag changes where advertised, UID inventory reconciles expunges. UID inventory itself scales with mailbox size; it contains no body/envelope content.
* Header fetch preloads ENVELOPE, FLAGS, UID, BODYSTRUCTURE and reply headers. Attachment detection walks cached MultipartDataSource parts from BODYSTRUCTURE, never content/inputStream. Bodies fetch separately with PEEK and a 25 MiB encoded-message ceiling; parsed MIME also has aggregate decoded-size and depth limits.
* Flags, MOVE, COPY fallback with UID EXPUNGE when UIDPLUS exists. Without UIDPLUS, fallback returns COPIED_AND_MARKED_DELETED and avoids global EXPUNGE. Permanent delete requires UIDPLUS.
* APPEND supports draft/sent flags and skips existing stable Message-ID to make retries idempotent. SMTP uses MIME alternative text/html, encoded addresses/subject/filenames, attachments and CID parts, reply headers and stable optional Message-ID.
* Dedicated cancellable IDLE socket; server SEARCH supports shared operator grammar with the data repository. Gmail X-GM-THRID is registered as an Angus FetchItem and batch-prefetched only when advertised (including an extension-parser fixture). Thread keys prefer Gmail IDs, then References/In-Reply-To/Message-ID, then normalized subject.
* Sanitizer allowlists structural tags/attributes and safe inline CSS properties/values. Removes active elements, events, refresh, stylesheets, srcset, background resources, CSS functions/escapes/URLs, unsafe link schemes. CID images only survive through a caller-supplied content URI map. HTTP(S) images require explicit opt-in. Renderer still must disable JavaScript and intercept requests.

## Verification

Remote builds only through scripts/gw. Focused engine and data verification passed; results below. Engine fixtures cover MIME encodings/attachment/CID round trips, malicious HTML, SMTP/IMAP sync and mutations, bounded pagination, roles, threading normalization, operator parsing. Robolectric :data tests exercise MIME serialization on an Android runtime as an Activation smoke test.

## Limits needing device/provider acceptance

JVM and Robolectric are not physical-device certification. Live Gmail/Infomaniak, real certificate chains, CONDSTORE/Gmail extension/IDLE behavior, and release R8 packaging need device acceptance. CSS stylesheets are stripped; safe inline layout styling is retained. Download currently caches all parts of an explicitly opened message (25 MiB limit), rather than streaming attachments individually. Search responses are bounded to 500 candidate messages per folder.

## Final focused result

`scripts/gw :mail:test --tests app.dot.mail.engine.EngineTest --tests app.dot.mail.engine.HtmlSanitizerTest :data:testDebugUnitTest` passed: **11 engine tests + 10 Robolectric tests, zero failures**. The 4 sanitizer tests include a matrix of active markup, unsafe schemes, event handlers, CSS fetch/escape mechanisms, CID mapping and remote opt-in. Gmail FetchItem parsing has its own fixture. Qwen subsequently fixed the autoconfig/text failures; combined full-module validation now passes (see below).

## Combined integration result

After Qwen’s fixes, `scripts/gw :mail:test :data:testDebugUnitTest` **passes: 99 mail tests + 10 data tests, zero failures** (remote BUILD SUCCESSFUL in 37s). This supersedes the earlier full-suite caveat.
