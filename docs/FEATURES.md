# Feature matrix

Legend: ✅ works and is covered by automated tests · 🟡 works, limited or only manually verified · ⏳ planned · ❌ not planned

Last updated with version 0.1.0.

## Accounts

| Feature | State | Notes |
|---|---|---|
| IMAP + SMTP, SSL/TLS and STARTTLS | ✅ | Live-tested against Infomaniak; GreenMail in tests |
| Password / app-password sign-in | ✅ | Encrypted with an Android Keystore AES-GCM key |
| Autodiscovery | ✅ | Built-in table for major providers → provider autoconfig → Mozilla ISPDB (domain only) → guess |
| Manual server settings | ✅ | |
| Multiple accounts, unified inbox | ✅ | |
| OAuth2 (Gmail, Microsoft) | ⏳ | Engine supports XOAUTH2; sign-in flow and client registration pending. Outlook/Hotmail personal accounts need it |
| Proton Mail | ❌ | Proton offers no IMAP access on Android |
| Multiple identities / aliases per account | ⏳ | Model exists; one identity per account today |
| POP3, Exchange ActiveSync, JMAP | ❌ / ⏳ | JMAP may come later |

## Mailbox

| Feature | State | Notes |
|---|---|---|
| Conversation threading | ✅ | Gmail thread id, References/In-Reply-To, subject fallback |
| Read / unread, star, archive, trash, spam, move | ✅ | Optimistic locally, queued for the server, replayed after reconnect |
| Bulk actions (multi-select) | ✅ | |
| Configurable swipe actions | ✅ | Archive, delete, read toggle, star toggle, none |
| Folders with special-use roles | ✅ | SPECIAL-USE plus name heuristics (Gmail, Infomaniak, …) |
| Folder create / rename / delete | ⏳ | |
| Search with operators, all accounts | ✅ | `from:` `to:` `subject:` `has:attachment` `is:unread` `is:starred`, phrases; local + server |
| Previews (snippets) in the list | ✅ | Prefetched for the newest 200 messages per folder, without marking as read |
| Sorting / filters / smart folders | ⏳ | |

## Reading

| Feature | State | Notes |
|---|---|---|
| Plain text, HTML, multipart MIME | ✅ | |
| Inline (cid:) images | ✅ | Served only from the message's own parts |
| Remote images blocked by default | ✅ | Per message or global opt-in |
| Attachments: open / share | ✅ | Through a FileProvider; any app can open them |
| Quoted text folding (plain text) | ✅ | English, French, German reply headers, Outlook blocks |
| Link destination confirmation | ✅ | Can be turned off |
| List-Unsubscribe | 🟡 | Offered from the menu; opens the link or mailto |

## Writing

| Feature | State | Notes |
|---|---|---|
| New, reply, reply all, forward | ✅ | |
| Draft autosave and recovery | ✅ | Local; not yet mirrored to the server's Drafts folder |
| Attachments (system picker, share-to) | ✅ | Copied into private storage at save time |
| Recipient suggestions and validation | ✅ | From addresses you've exchanged mail with; no contacts permission |
| Undo send (0–30 s) | ✅ | |
| Scheduled send | 🟡 | Quick choices (this evening, tomorrow, Monday); exact timing depends on Android's scheduler |
| Signatures | ⏳ | |
| Rich text composing | ⏳ | Plain text today |

## Sync & system

| Feature | State | Notes |
|---|---|---|
| Incremental header sync (UIDVALIDITY/UIDNEXT, CONDSTORE) | ✅ | Bodies downloaded on demand |
| IMAP IDLE | 🟡 | INBOX, while the app is in the foreground |
| Background sync | 🟡 | Every 15 min when online (WorkManager); no always-on push yet |
| New-mail notifications with actions | 🟡 | Per-account channels; Mark read / Archive |
| Outbox with duplicate-safe retry | ✅ | Ambiguous SMTP failures need your confirmation before resending |
| Default mail app (`mailto:`), share-to | ✅ | |
| App shortcuts | ✅ | Compose, Search |
| Home-screen widget | ⏳ | In progress |
| App lock (biometric / device credential), hide in Recents | ✅ | |

## Not included, on purpose

Analytics, crash reporting services, ads, Google Play Services, Firebase, any server operated by us.
