# Task 4 Report — Enforce Secure TLS During Account Discovery & Credential Verification

**Author:** Gemini (`wt-gemini`, branch `gemini/work`)  
**Scope:** `mail/src/main/kotlin/app/dot/mail/engine/autoconfig/**`, `mail/src/test/kotlin/app/dot/mail/engine/TlsSecurityTest.kt`, `data/src/test/kotlin/app/dot/mail/data/TlsVerifyRegressionTest.kt`, plus audit & exact diffs for `MailClient.kt`, `RealMailGraph.kt`, and `AccountSetup.kt`.

---

## 1. Audit Findings & Fixes by Requirement

### 1.1 Discovery Must Never Produce Silent `PLAIN` or Opportunistic `STARTTLS`
- **Finding A (`AutoconfigXml.kt:61-65` — Fixed):**
  `pick()` previously scored candidates as `typeScore * 10 + socketRank.getValue(socket)`. Because `imap` had `typeScore = 2` (20 pts) and `PLAIN` had `socketRank = 1` (total 21), an `<incomingServer type="imap"><socketType>plain</socketType>` scored higher than an encrypted fallback or tied with lower-ranked entries. Furthermore, `<socketType>STARTTLS</socketType>` accompanied by `<requireTls>false</requireTls>` (opportunistic STARTTLS) was treated identically to strict `STARTTLS`.
  - **Fix:**
    - Added `tlsBonus = if (socket != SocketType.PLAIN) 100 else 0` in `pick()` so any `SSL` or `STARTTLS` entry always outranks `PLAIN`.
    - Added `parseServerSocket(el)` which inspects `<requireTls>` / `<tlsRequired>`: if explicitly `"false"` or `"no"`, `<socketType>STARTTLS</socketType>` is treated as `SocketType.PLAIN` (rejected in favor of strict TLS, or flagged `insecure = true` if nothing else exists).
- **Finding B (`AutoDiscovery.kt:33-58` & `Types.kt` — Fixed):**
  `AutoDiscovery.discover()` previously returned the first non-null XML result from `autoconfig.<domain>` or `.well-known/autoconfig/mail` even if that XML only offered `socketType=plain`, without checking whether MX/ISPDB had an encrypted config and without flagging the result as insecure.
  - **Fix:**
    - Added `val insecure: Boolean = imap.socket == SocketType.PLAIN || smtp.socket == SocketType.PLAIN` to `DiscoveredConfig` (`Types.kt`).
    - Updated `AutoDiscovery.discover()` so any `PLAIN` result (`cfg.insecure == true`) from provider autoconfig, `.well-known`, MX autoconfig, or ISPDB is held only as `insecureFallback` while discovery continues checking remaining authoritative sources for a secure (`!cfg.insecure`) configuration.
    - Only if no encrypted configuration exists across all authoritative sources is `insecureFallback` returned, with `insecure = true` and `hint = INSECURE_PLAIN_HINT` (`"Provider only advertises unencrypted connections; confirm security settings before continuing"`).
    - `guess()` (`Source.GUESS`) and `BuiltInProviders` (`Source.BUILTIN`) only ever produce `SocketType.SSL` / `SocketType.STARTTLS` (`insecure = false`).

### 1.2 STARTTLS Required, Hostname Verification, & TLS 1.2+ Hardening (`MailClient.kt` & `RealMailGraph.kt`)
- **Verified in `MailClient.kt:20-38`:**
  - `mail.$protocol.ssl.enable` = `(endpoint.security == TransportSecurity.SSL_TLS).toString()`
  - `mail.$protocol.starttls.enable` = `(endpoint.security == TransportSecurity.STARTTLS).toString()`
  - `mail.$protocol.starttls.required` = `(endpoint.security == TransportSecurity.STARTTLS).toString()`
  - `mail.$protocol.ssl.checkserveridentity` = `"true"`
  - No trust-all socket factories or `mail.$protocol.ssl.trust` wildcards exist anywhere in `:mail` or `:data`.
- **Gap 1 in `MailClient.kt:29` (Diff for Codex `t3` below):**
  `MailClient.session()` does **not** set `mail.$protocol.ssl.protocols`. Explicitly setting `setProperty("mail.$protocol.ssl.protocols", "TLSv1.3 TLSv1.2")` guarantees that Angus Mail's `SocketFetcher.configureSSLSocket` restricts `SSLSocket.enabledProtocols` to TLS 1.2 and TLS 1.3 only (forbidding `SSLv3`, `TLSv1`, and `TLSv1.1` on both implicit TLS and STARTTLS sockets). We also recommend explicitly setting `mail.debug = "false"` and `mail.debug.auth = "false"`.
- **Gap 2 in `RealMailGraph.kt:95-102` (Diff for Codex `t3` below):**
  `RealMailGraph.verify()` classifies `VerifyResult.CertificateProblem` by checking `generateSequence<Throwable>(e) { it.cause }.any { it is SSLException }`. Expanding this predicate to `it is SSLException || it is java.security.cert.CertificateException || it is java.security.cert.CertPathBuilderException` ensures any certificate/hostname validation failure is always surfaced as `VerifyResult.CertificateProblem`.
- **Gap 3 in `RealMailGraph.kt:88-94` & `AccountSetup.kt:55-68` (Diff for Codex `t3` & Claude `t1` below):**
  When `AutoDiscovery.discover()` returns an `insecure = true` config (`SocketType.PLAIN` -> `Security.NONE`), `AddAccountViewModel.connect()` in `AccountSetup.kt` currently proceeds directly to `repo.verify(config, s.password)` if `found != null`. If `found.imap.security == Security.NONE || found.smtp.security == Security.NONE`, `AddAccountViewModel.connect()` should route the user to `SetupState.Step.Manual` (which already displays the red warning banner `"Unencrypted — password and mail sent in plain text"`) so the user must explicitly confirm before any connection is attempted.

### 1.3 Autoconfig HTTP Fetcher (`ConfigFetcher.kt`)
- **Verified & Hardened (`ConfigFetcher.kt:12-45` — Fixed):**
  - `HttpsConfigFetcher` rejects any URL whose scheme is not `https` (including `http://autoconfig.<domain>` and any `https -> http` redirect).
  - Hardened the URI check in `HttpsConfigFetcher.get()` to also reject blank hosts (`!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()`).
  - Enforces `instanceFollowRedirects = false` with manual redirect validation up to `maxRedirects = 3`, `timeoutMs = 8_000` (connect & read), and `maxBytes = 256 * 1024` (256 KiB cap).

### 1.4 Credential Protection on `verify()` Path
- **Verified & Regression-Tested:**
  - Neither `MailClient` nor `RealMailGraph` logs passwords or OAuth tokens, and `Credentials` (`Models.kt`) overrides `toString()` to `"Credentials(REDACTED)"`.
  - When `STARTTLS` is omitted from `CAPABILITY`/`EHLO`, rejected with `BAD`/`454`, or fails TLS handshake (corrupt TLS record, self-signed cert, or wrong-hostname cert), Angus Mail aborts before `LOGIN` / `AUTHENTICATE PLAIN` / `AUTH PLAIN`. Our fake-socket regression tests assert on the raw wire transcript that neither cleartext passwords, Base64-encoded passwords, SASL PLAIN blobs, nor `LOGIN`/`AUTHENTICATE`/`AUTH` commands are ever transmitted.

---

## 2. Exact Diffs for Codex (`t3`) and Claude (`t1`)

### 2.1 Diff for `mail/src/main/kotlin/app/dot/mail/engine/MailClient.kt` (Codex `t3`)

```diff
--- a/mail/src/main/kotlin/app/dot/mail/engine/MailClient.kt
+++ b/mail/src/main/kotlin/app/dot/mail/engine/MailClient.kt
@@ -27,7 +27,10 @@ class MailClient(val timeoutMs: Int = 30_000) {
             setProperty("mail.$protocol.starttls.enable", (endpoint.security == TransportSecurity.STARTTLS).toString())
             setProperty("mail.$protocol.starttls.required", (endpoint.security == TransportSecurity.STARTTLS).toString())
             setProperty("mail.$protocol.ssl.checkserveridentity", "true")
+            setProperty("mail.$protocol.ssl.protocols", "TLSv1.3 TLSv1.2")
             setProperty("mail.$protocol.auth", "true")
+            setProperty("mail.debug", "false")
+            setProperty("mail.debug.auth", "false")
             setProperty("mail.imap.peek", "true")
             setProperty("mail.imap.enablecondstore", "true")
             if (credentials.oauth2) setProperty("mail.$protocol.auth.mechanisms", "XOAUTH2")
```

### 2.2 Diff for `data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt` (Codex `t3`)

```diff
--- a/data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt
+++ b/data/src/main/kotlin/app/dot/mail/data/RealMailGraph.kt
@@ -96,7 +96,12 @@ class RealMailGraph(
         try { client.verify(config.imap.endpoint(), config.smtp.endpoint(), Credentials(password, config.authType == AuthType.OAUTH2)); VerifyResult.Ok }
         catch (e: AuthenticationFailedException) { VerifyResult.AuthFailed("Authentication failed. Check your password or app password.") }
         catch (e: Exception) {
-            if (generateSequence<Throwable>(e) { it.cause }.any { it is SSLException }) VerifyResult.CertificateProblem("Server TLS certificate could not be verified")
+            val isCertProblem = generateSequence<Throwable>(e) { it.cause }.any {
+                it is SSLException ||
+                    it is java.security.cert.CertificateException ||
+                    it is java.security.cert.CertPathBuilderException
+            }
+            if (isCertProblem) VerifyResult.CertificateProblem("Server TLS certificate could not be verified")
             else VerifyResult.ConnectionFailed("Could not connect to the mail server")
         }
     }
```

### 2.3 Diff for `app/src/main/kotlin/app/dot/mail/ui/onboarding/AccountSetup.kt` (Claude `t1`)

When `discover()` returns an insecure (`Security.NONE`) fallback config, force the user through `SetupState.Step.Manual` (where the red plaintext warning is shown) instead of silently verifying over plaintext in `Step.Credentials`:

```diff
--- a/app/src/main/kotlin/app/dot/mail/ui/onboarding/AccountSetup.kt
+++ b/app/src/main/kotlin/app/dot/mail/ui/onboarding/AccountSetup.kt
@@ -57,6 +57,18 @@ class AddAccountViewModel(
         viewModelScope.launch {
             val config = if (s.step == SetupState.Step.Credentials) {
                 val found = s.discovered ?: repo.discover(s.email.trim())
+                if (found != null && (found.imap.security == Security.NONE || found.smtp.security == Security.NONE)) {
+                    state.update {
+                        it.copy(
+                            busy = false,
+                            discovered = found,
+                            imap = found.imap,
+                            smtp = found.smtp,
+                            step = SetupState.Step.Manual,
+                            error = found.hint ?: "Provider only advertises unencrypted connections; confirm security settings before continuing",
+                        )
+                    }
+                    return@launch
+                }
                 if (found != null && found.authType == AuthType.OAUTH2) {
                     state.update {
                         it.copy(
```

---

## 3. Regression Tests Added

1. **`mail/src/test/kotlin/app/dot/mail/engine/TlsSecurityTest.kt` (12 tests, all passing):**
   - `discovery rejects plain socket when encrypted alternative exists in xml`
   - `discovery rejects opportunistic starttls in favor of required tls`
   - `plain-only provider autoconfig yields to secure ispdb and marks insecure when nothing else exists`
   - `imap omitting STARTTLS from CAPABILITY fails verify without sending credentials`
   - `imap advertising STARTTLS but rejecting upgrade fails verify without sending credentials`
   - `imap accepting STARTTLS command but corrupting tls handshake fails with SSLException`
   - `smtp omitting STARTTLS from EHLO fails verify without sending credentials`
   - `smtp advertising STARTTLS but rejecting upgrade fails verify without sending credentials`
   - `self-signed certificate on implicit TLS fails with SSLException and sends no credentials`
   - `wrong-hostname certificate fails even when cert is trusted by default SSLContext`
   - `self-signed certificate on IMAP STARTTLS fails with SSLException and sends no credentials`
   - `wrong-hostname certificate on IMAP STARTTLS fails and sends no credentials`
2. **`data/src/test/kotlin/app/dot/mail/data/TlsVerifyRegressionTest.kt` (3 tests, all passing):**
   - `verify returns CertificateProblem on self-signed certificate and never sends credentials`
   - `verify returns CertificateProblem on wrong-hostname certificate even when cert is trusted`
   - `verify fails when STARTTLS is omitted or rejected and never sends credentials`
