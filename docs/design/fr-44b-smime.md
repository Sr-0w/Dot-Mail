# FR-44b — S/MIME design proposal

**Status:** proposal for owner review; no implementation or Room change authorized.

## Goal and boundaries

Add interoperable S/MIME signing and encryption to the native Kotlin/Compose mail client. The user owns the private key and decides when content is signed or encrypted. Support new messages, replies and forwards; allow per-account signing and encryption defaults; validate a usable certificate for every encryption recipient; and never silently send a requested protected message in cleartext.

This proposal covers key/certificate handling, CMS/MIME construction and verification, recipient-certificate policy, defaults, failure behavior and interoperability evidence. It does not choose a Room schema or authorize implementation. Both would need owner approval after this note.

## Standards and library gate

Implement the S/MIME 4.0 profile in [RFC 8551](https://datatracker.ietf.org/doc/html/rfc8551), using CMS as specified by [RFC 5652](https://datatracker.ietf.org/doc/html/rfc5652). The RFC defines MIME packaging for `multipart/signed`, detached PKCS#7 signatures and `application/pkcs7-mime` CMS objects. Start with SHA-256 or stronger for signatures; reject newly generated RSA signing keys below 2048 bits. For encryption, honor authenticated, recently verified recipient capabilities where available; with unknown capabilities RFC 8551 recommends AES-256-GCM, with AES-128-CBC as the documented compatibility fallback. Define a minimum acceptable encryption policy and fail with a clear choice if a recipient/capability set cannot meet it; never silently weaken the policy. RFC 8551 notes that recipient groups with non-overlapping capabilities require multiple messages, and warns against sending a weaker copy of the same plaintext. Decide the exact profile and interoperability behavior in tests rather than enabling every algorithm the CMS library happens to expose.

[Bouncy Castle’s Java CMS APIs](https://downloads.bouncycastle.org/java/docs/bcpkix-jdk14-javadoc/org/bouncycastle/cms/package-summary.html) are a plausible implementation candidate: the project documents CMS signing and enveloping APIs. However, this is **not evidence that the ordinary Android/JVM artifact has had an independent security audit**. The [NIST validation record](https://csrc.nist.gov/projects/cryptographic-module-validation-program/certificate/4943) applies to the specifically listed Bouncy Castle FIPS Java API/module and its evaluated configuration, not automatically to the regular `bcprov`/`bcpkix` Android dependency or this app’s CMS integration.

Before adding any cryptographic dependency, require owner review of the exact artifact/version, license, Android compatibility, maintenance/security-advisory process, transitive dependencies, and available independent audit or certification evidence. If the project’s “audited libraries only” requirement cannot be evidenced for a candidate, stop and return with the evidence gap; popularity, source availability, passing tests, or an audit of a different FIPS module do not count as an audit. Do not write custom CMS, X.509 path validation, or cryptographic primitives as a workaround.

## Private-key options

| Option | Benefits | Costs and risks | Proposal |
|---|---|---|---|
| Android `KeyChain` | System chooser and user-controlled key access; app stores an alias, not an exported private key. The API exposes certificate-chain and private-key access and says those calls may take seconds and must run off the main thread. | Depends on the user/device having imported a suitable key; alias grants can be revoked and chooser can return no selection. Device/OEM behavior and hardware backing vary. It is a system service, not an app-owned PKCS#12 import flow. | Preferred first path. Use `choosePrivateKeyAlias` from an Activity, then retrieve alias key/chain on `Dispatchers.IO`; handle cancellation, revoked grants, unavailable keys and key-chain errors explicitly. Never assume hardware-backed storage. |
| User-selected PKCS#12 (`.p12`/`.pfx`) import | Portable and works when no certificate is installed in KeyChain; matches common enterprise provisioning. | App takes responsibility for password handling, parsing, key lifetime, backup/export policy, encrypted at-rest storage, replacement/deletion and recovery. Android Keystore generally cannot simply import every existing private key while preserving its identity. | Optional follow-up only if KeyChain is insufficient. Use Storage Access Framework; ask for password only for the import; do not persist it. Keep key material out of Room, logs, analytics and backups. Encrypt an app-private key container with a per-install Android Keystore wrapping key, and explain reinstall/device-loss consequences. Validate contents before storing. |

Do not silently copy a KeyChain private key into app storage. Certificate chains and selection metadata may be cached, but key access remains mediated by the selected source. Keep signing and decryption key references distinct: a person may use separate certificates for signing and confidentiality.

## Message modes and MIME behavior

Represent the user’s explicit compose choice as one of `NONE`, `SIGN`, `ENCRYPT`, or `SIGN_AND_ENCRYPT`; do not infer a stronger mode from defaults after the user has changed the choice. For signing, use S/MIME `multipart/signed` with a detached CMS signature as the interoperability-first candidate. For encryption, use CMS `EnvelopedData` in `application/pkcs7-mime; smime-type=enveloped-data`. For both, sign the fully composed MIME entity first, then encrypt that signed entity for recipients (including the sender’s own configured decryption certificate when selected), so the recipient decrypts before signature verification. Confirm exact encodings against independent mail clients before fixing the profile.

Construct the complete MIME entity before signing; any later byte-changing rewrite invalidates the signature. Do not include generated S/MIME wrapper parts in the signed content. Existing attachment, multipart/alternative, inline image and transfer-encoding handling must round-trip. Keep protected headers out of scope unless separately designed: RFC interoperability commonly leaves subject and outer routing headers visible.

Replies and forwards follow the active compose mode. They do not inherit the original message’s security mode silently. Per-account defaults may preselect signing and/or encryption for a new compose; the composer must show the active mode before send, and users may explicitly change it. Do not introduce a global default in this FR unless requested.

## Recipient certificate validation

Encryption is all-or-nothing for the complete recipient set. Before creating the outbox item, resolve and validate a certificate for every `To`, `Cc` and `Bcc` recipient (and any configured self-copy recipient):

1. Match the exact normalized mailbox address to a certificate identity (including applicable `rfc822Name` SAN; do not accept a display-name or substring match). If several certificates match, show the candidates and let the user choose; never pick a stale/ambiguous certificate invisibly.
2. Check certificate validity period, `keyUsage` for key encipherment/key agreement as appropriate, `extendedKeyUsage` if present, public-key type/size and the selected CMS algorithm profile.
3. Build and validate the chain against a documented trust source and evaluate revocation according to a documented offline policy. No network lookup or unavailable revocation service may be represented as “valid”; report `revocation unknown` separately. A self-signed or untrusted certificate is not accepted by default. Any future explicit trust override must identify the recipient/certificate fingerprint and be a deliberate, visible user action.
4. Recheck certificate identity, validity and private-key availability immediately before cryptographic use. Certificate expiry/revocation, address mismatch, unsupported algorithm, missing recipient cert, or unavailable key blocks encryption.

Signing is separate from recipient encryption validation. Before signing, verify that the chosen signing certificate is valid for signing, its private key is accessible, and its identity is suitable for the selected account/sender identity. The UI must distinguish “signed” from “signature verified”; certificate-chain trust, identity match, validity, revocation and cryptographic integrity are separate verdict dimensions.

## No silent downgrade and outbox safety

If signing/encryption was requested and any precondition fails, keep the message in compose and show a specific recoverable error. Never queue an unprotected fallback, silently drop recipients, omit Bcc, substitute another account’s key, or downgrade `SIGN_AND_ENCRYPT` to only one operation. CMS recipient identifiers in a shared envelope can disclose recipient certificate identities, including Bcc identities. Before shipping, choose and interop-test a Bcc-safe packaging strategy (for example separate per-recipient encrypted MIME variants); do not assume that SMTP Bcc header removal alone hides CMS recipient metadata. Per-recipient packaging may also be needed when recipients have incompatible capabilities; never send a weaker duplicate plaintext copy to make every recipient compatible. The user may deliberately change the mode to `NONE` or another mode after seeing the failure; require a second explicit send action. Persist the selected protection mode with the outbox item so retries cannot change it. A protected payload must be fully built and validated before it becomes sendable; drafts must not upload or mirror plaintext when the user chose encryption, consistent with the existing OpenPGP fail-closed rule.

## Defaults and persistence decision needed

Per-account defaults should be independent booleans (`signByDefault`, `encryptByDefault`) with the effective compose mode shown explicitly. Encryption default can only be selected when the recipient-certificate resolution path is available; it does not waive validation. Replies and forwards use the same defaults as new messages and preserve the user’s visible override.

Persisting defaults, KeyChain aliases and imported certificates/keys needs a data model. **No Room schema change is included or proposed for immediate implementation.** After this design is accepted, return a separate storage proposal (including migration, key deletion/replacement, backup policy and recovery) and obtain explicit approval before touching the schema. Prefer platform aliases and encrypted app-private files over storing private key bytes or PKCS#12 blobs in Room.

## Verification plan for a later implementation

- Unit tests for MIME mode mapping and construction: sign, encrypt, sign-then-encrypt, no security, nested multipart/alternative, attachments and inline parts.
- Fail-closed tests for absent/ambiguous/mismatched/expired/untrusted/revoked-or-unknown recipient certificates, unsupported algorithms, missing/revoked KeyChain grants, signing failure, and outbox retry. Assert that no plaintext sendable item is created and all recipients, including Bcc, are accounted for.
- Verification tests with valid, invalid, expired and tampered detached/opaque CMS signatures; report integrity and trust independently.
- Android integration tests for KeyChain selection cancellation, unavailable alias and background-thread access. PKCS#12 tests only if that path is approved.
- Interoperability fixtures and controlled send/receive tests with Thunderbird, Apple Mail and Outlook, for sign-only, encrypt-only and sign+encrypt; include UTF-8, attachments, inline images, multiple recipients and reply/forward. Record exact client versions and test certificates. Never use real user mail or private keys in fixtures.
- Dependency/license/advisory review and a reproducible release/R8 build after the exact cryptographic artifact is approved.

## Open decisions for owner

1. Is the proposed KeyChain-first path acceptable, with PKCS#12 import considered only as a separately approved fallback?
2. What audit/certification evidence satisfies “audited libraries only” for an Android CMS artifact? The regular Bouncy Castle Java CMS library is only a candidate until that evidence is reviewed.
3. What is the product trust policy for recipient certificates and offline revocation? This note proposes strict chain/identity/usage checks, with unknown revocation shown distinctly and no implicit trust override.
4. Approve the MIME profile and sign-then-encrypt nesting, then separately approve any persistence/schema proposal before implementation.

## References

- [RFC 8551 — S/MIME 4.0 Message Specification](https://datatracker.ietf.org/doc/html/rfc8551)
- [RFC 5652 — Cryptographic Message Syntax (CMS)](https://datatracker.ietf.org/doc/html/rfc5652)
- [Android `KeyChain` API](https://developer.android.com/reference/android/security/KeyChain)
- [Bouncy Castle Java documentation](https://www.bouncycastle.org/documentation/documentation-java/)
- [Bouncy Castle CMS API](https://downloads.bouncycastle.org/java/docs/bcpkix-jdk14-javadoc/org/bouncycastle/cms/package-summary.html)
- [NIST CMVP certificate 4943 — specific Bouncy Castle FIPS Java API module](https://csrc.nist.gov/projects/cryptographic-module-validation-program/certificate/4943)
