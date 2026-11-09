# FR-14: optional JMAP alongside IMAP/SMTP

Proposed design, followed by an engine-only, read-only first slice. IMAP/SMTP stays the default. Existing accounts are never converted, JMAP is never required, and enabling it must be explicit. Room v10 belongs to t14's historical thread repair; **this work changes no schema without Claude's approval**.

## Boundary and first delivery

Keep `MailClient` as the application's engine facade. Add `MailClient.jmap(...)`, returning a typed JMAP connection/client; leave its existing IMAP/SMTP methods unchanged. The first delivery provides Session discovery, mail-account selection, Mailbox/get, Email/query, Email/get and Email/changes against an HTTP fixture server. No data-layer migration, account UI, sending, mutable flags, push service or real-provider compatibility claim in that slice.

JMAP uses opaque string identifiers and state tokens. Do not convert them to a Long UID, hash them into UID slots, or feed them to IMAP operations. The new engine DTOs retain remote account/mailbox/email/thread/blob IDs separately; local numeric UI IDs remain a data-layer concern. Expose typed failures for transport/authentication, unsupported capability, malformed/oversized response, method error, missing objects and unusable sync state. Tests must distinguish HTTP success from JMAP method success.

Use injected transport and credential supply. The production transport is HTTPS with platform certificate/hostname checking; fixture transport may explicitly use loopback HTTP. Bounded reads, cancellation, deadlines, a small concurrency cap and server-advertised limits are required. Reuse the catalog's Apache-2.0 kotlinx-serialization JSON library in `:mail`; no new networking framework is needed. No JSON serialization compiler plugin is needed for a bounded DOM parser.

## Session discovery and authentication

The Session document identifies capabilities, remote accounts and service endpoints. Core request/object limits are advertised, and account IDs are distinct from login names. Standard discovery includes `/.well-known/jmap`. See [RFC 8620: Session/discovery](https://www.rfc-editor.org/rfc/rfc8620#section-2).

For the first Fastmail profile, use its documented `https://api.fastmail.com/jmap/session` and a user-provided bearer API token. Fastmail distinguishes this token from an IMAP app password and recommends OAuth for apps distributed to other users. Public sign-in needs our own provider registration. See [Fastmail developer documentation](https://www.fastmail.com/dev/).

Proposed setup: an off-by-default experimental account option, explicit Session URL/token entry, token stored through the existing account-bound Keystore vault. Add an account transport discriminator with IMAP/SMTP as its default; keep authentication type separate from protocol. No token in account JSON, URLs, logs, navigation state or credential-free backups. Later Fastmail OAuth must use our registered client and a reviewed native redirect/refresh flow; the existing Gmail/Microsoft OAuth configuration grants no Fastmail access.

Never forward a bearer token to an arbitrary redirect or Session-provided origin. Bind approved HTTPS origins to the configured provider: the built-in Fastmail profile approves its reviewed API origin; generic/self-hosted setup requires explicit origin approval before authenticated requests. Discovery without credentials precedes trust decisions. Do not blanket-disable private LAN servers, but do not let discovery silently redirect credentials to them. Session refresh may update capabilities; it must not silently expand the credential-origin allowlist. Select an explicit mail-capable remote account; use an unambiguous advertised primary account as a default, not every shared account.

## Email/get and changes: data-layer mapping proposal

Mail methods expose mailbox memberships, email metadata, remote thread IDs, keyword flags and opaque sync states. Query state and object state serve different purposes. See [RFC 8621: Mailbox and Email](https://www.rfc-editor.org/rfc/rfc8621).

Proposed adapter mapping:

| JMAP value | Dot Mail representation |
|---|---|
| Remote account + mailbox ID | Stable FolderRow ID through a sidecar identity map; names/parents/known roles are metadata |
| Remote account + email ID | Stable MessageRow ID; no relationship to an IMAP UID |
| Remote account + thread ID | Stable ThreadRow ID, authoritative for that account; no subject fallback |
| `$seen` / `$flagged` | unread inverse / starred |
| Subject, addresses, date, preview | Existing header JSON, sentAt and Unicode-safe snippet pipeline |
| Blob/body structure | Lazy body/attachment references; existing sanitizer and private cache remain the rendering/storage boundary |
| Email/mailbox state | Separate persisted cursors, never UIDNEXT/UIDVALIDITY |

The current MessageRow has a non-null folderId and UID, a unique `(folderId,uid)` index, and folder-scoped IMAP queue semantics. An email with several JMAP memberships cannot safely fit by choosing one arbitrary folder or inventing a numeric remote UID. Per-folder duplicate rows would also complicate cache ownership and recreate the cross-folder operation bugs fixed by FR-04.

**Preferred schema proposal for lead review:** normalize message membership, add account-scoped remote identity/cursor tables, and make IMAP location fields optional for JMAP records while retaining a non-null validated IMAP location for existing accounts. ThreadRow and local message IDs remain the public identities. Introduce a typed `requireImapLocation` boundary for old wire operations, then teach folder queries/actions to join memberships. Existing IMAP rows backfill one membership. Any eventual migration must preserve outbox/draft pins, references, FTS IDs, notification IDs and body/attachment ownership; add v9/v10 fixtures and multi-membership action/eviction tests. Allocate a version only after Claude approves and t14's v10 lands. This proposal is not an implemented migration.

The first engine does not require this migration: it returns typed JMAP objects and changed/destroyed IDs for fixture validation. Data integration follows after the mapping is approved; do not insert experimental data into legacy UID columns as a shortcut.

Proposed sync: initial bounded query pages (50/page, first window at most 500) plus metadata-only get; get bodies explicitly on demand. Store each applied change batch and its new object state in one Room transaction. Loop when more changes remain, bounded per worker invocation. Fetch current metadata for created/updated IDs, reconcile memberships/flags, then rebuild only touched aggregates. Preserve local queued intent. A destroyed email removes its remote memberships, while pinned/draft/outbox/unsynced data retains protected local content. Never infer global deletion from an incomplete query window, a partial get, a transient error, or a missing page.

On an unusable changes cursor, stage a bounded replacement scan by generation; publish only validated pages and retire the previous complete snapshot conservatively. Concurrent responses use a generation/state check before commit. A mailbox query cursor cannot replace the account Email/changes cursor. Initial list results remain unthreaded until all needed conversation members are available; a representative result must not imply a fully fetched conversation.

## Push: EventSource, with polling fallback

Core JMAP advertises an EventSource endpoint and state-change events; mail changes remain the source of truth. See [RFC 8620: push notifications](https://www.rfc-editor.org/rfc/rfc8620#section-7).

Proposed integration: the existing foreground/instant-notification owner selects IMAP IDLE or JMAP EventSource by account transport. No extra permanent service or automatic enabling. One authenticated stream per Session, scoped to approved accounts; use headers for authentication, never a token query parameter. Bound line/event size, parse incremental SSE frames, honor comments/heartbeats and cancellation, and close promptly when the account or mode stops. Coalesce invalidations per account, then run changes from the persisted cursor. Event payload/state is a hint, not mail content to trust or a replacement for transactional sync.

Reconnect with jitter/backoff and network callbacks; stop authentication loops until reauthentication. Respect Wi-Fi constraints. Periodic WorkManager sync still recovers after missed events, process death or a server without push. Tests cover split UTF-8 frames, multiline events, oversized events, silent/disconnected peers, repeated state hints and lifecycle cancellation before this path can drive real notifications.

## Submission and offline operations (later write slice)

JMAP Mail defines identities and submission methods. See [RFC 8621: EmailSubmission](https://www.rfc-editor.org/rfc/rfc8621#section-7).

Proposed sending flow: keep the existing persisted undo/scheduled outbox, build MIME with its identity/signature/quote rules, upload/import it, persist its email/blob identity, then submit using an allowed remote identity and envelope. Record phase boundaries before network writes. Provider-managed Sent/draft updates must use the JMAP operation model; never also run SMTP Sent APPEND for a JMAP account. Flags, moves and server drafts need their own queued JMAP mutations with membership-scoped patches, not the IMAP UID replay queue.

A creation ID in one request is not a guaranteed cross-request idempotency key. A lost submission response is an uncertain send: reconcile against authoritative server state when possible and otherwise use the existing duplicate-risk outbox review. Never blindly resubmit or fall back to SMTP. Tests must simulate a server accepting a write and dropping its response, clock changes, undo expiry and restart. No write API is enabled by the read-only first engine slice, even with a write-capable token.

## Delivery and acceptance

1. This note + engine read methods behind MailClient: Session/mailbox/query/get/changes, bounded HTTPS, injected fixture transport, protocol-limit and credential-boundary tests. IMAP tests stay green. No schema/account UI change.
2. Approved data mapping and opt-in setup: repository capability routing, transactional cursors/memberships, Room migration fixtures, existing storage/search protections and translated retry/auth state. Read operations never mark seen. Mutating UI actions remain disabled until their backend exists.
3. Lifecycle-owned EventSource with polling fallback; fixture reconnect/lifecycle tests before phone/live checks.
4. Separately reviewed submission/drafts/offline-write phases with uncertainty protections and interoperability tests.

Live Fastmail validation requires a separately provided test account/token and explicit authorization; the existing Infomaniak account does not establish JMAP compatibility. No real credentials or device access are needed for the fixture-first engine. Report tested capabilities and limitations before claiming provider support.

## Implemented first-slice API and verification

`MailClient.jmap(config: JmapConfig, credentials: Credentials, transport: JmapHttpTransport = HttpsJmapTransport(timeoutMs)): JmapClient` is explicit: credentials represent a bearer API/OAuth token for this protocol. Token refresh remains the caller's responsibility; reconstruct the client with current credentials after refresh. No existing account uses this method automatically.

- `session(): JmapSession`; `session.account(id?)` chooses an explicitly requested/primary/unambiguous account.
- `mailboxes(session, accountId?, ids?): JmapObjects<JmapMailbox>`.
- `queryEmails(session, accountId?, mailboxId?, position=0, limit=50): JmapQuery`.
- `emails(session, ids, accountId?): JmapObjects<JmapEmail>`; empty gets are valid and can establish an initial email state without downloading content.
- `changes(session, sinceState, accountId?, maxChanges=200): JmapChanges` returns one page. `upsertIds` excludes destroyed IDs, including legal overlaps between change lists. The data coordinator owns pagination and transactional state advancement.

No bodyValues, EventSource connection, submission, automatic writes or generic arbitrary-method entry point is exposed. Result objects retain Session state for a coordinator to detect capability changes. Missing objects are distinct from missing/malformed batch results. A `cannotCalculateChanges` method error signals resync; opaque states are never interpreted as counters. HTTP auth/throttle/transient failures also map into the existing ConnectionFailure classification.

Limits in this first implementation: metadata responses at most 2 MiB, request bodies at most 1 MiB or the smaller advertised limit, gets/query pages at most 200 objects, JSON nesting at most 64, opaque state tokens at most 4096 characters (empty states supported), two in-flight requests per client (one when advertised). Standard well-known URL construction is provided; authenticated redirects are refused rather than followed. Session/API URLs must be HTTPS without query parameters, userinfo or fragments; cross-origin API endpoints require explicit approval. Query-bearing CGI endpoints and automatic SRV discovery are not implemented. The fixture retains a logical HTTPS origin and injects a loopback HTTP connection factory; production has no insecure-TLS mode.

The fixture suite has 21 tests for discovery/account selection, limits (including chunked oversized responses), membership/flags, Unicode emoji/CJK/combining marks, paging, empty gets, partial/mismatched responses, legal change-list overlap, method-error privacy, Session changes, origin trust and forged Sessions, redirect refusal, JSON injection, credential-safe diagnostics, concurrency and prompt cancellation of a real in-flight socket. The new dependency is the already catalogued `kotlinx-serialization-json` (Apache-2.0), now also used by :mail.

`scripts/gw :mail:test :data:testDebugUnitTest :app:assembleDebug` passed on main base 904ef5d (3m29). After rebasing onto main 0259598 (including Room v10/#76), `scripts/gw :mail:test :data:testDebugUnitTest :app:assembleRelease` passed (4m33): mail 453 tests / 5 skipped, data 225 / 4 skipped, no failures; all 21 JMAP fixture tests executed. The release was shrunk with R8. No live Fastmail, data migration, account UI or phone test was performed.
