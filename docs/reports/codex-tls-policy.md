# Mail TLS policy

All IMAP/IMAPS/SMTP/SMTPS session property namespaces restrict protocols to TLS 1.3 and TLS 1.2. Session debug and authentication debug are explicitly disabled. Existing strict STARTTLS and hostname checking remain enabled.

Compatibility correction to the proposed audit diff: Angus passes the configured names directly to SSLSocket.setEnabledProtocols. TLS 1.3 is available on Android 10+, while minSdk is 26. The allowlist is therefore intersected with the runtime SSL provider's supported protocols: Android 26–28 keeps TLS 1.2, modern providers use TLS 1.3/1.2, and a provider with neither fails closed. No fallback enables TLS 1.1 or older. Sources: [Angus SocketFetcher](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/SocketFetcher.java), [Android TLS](https://developer.android.com/privacy-and-security/security-ssl).

Verification classifies SSLException, CertificateException and CertPathBuilderException, including wrapped Jakarta nextException chains, as CertificateProblem. Traversal tolerates cause cycles and displays fixed user-safe messages. Authentication and connection failures retain distinct result types.

Tests cover modern/older provider protocol sets, fail-closed behavior, all session security variants and property namespaces, debugging, certificate forms/chains/cycles and error separation. Existing TLS socket and account-verification regression suites are included in scripts/gw :mail:test :data:testDebugUnitTest. No new dependency or schema change; no phone access.
