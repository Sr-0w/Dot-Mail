# FAQ — adding your account & troubleshooting

Dot Mail connects directly to your mail server over IMAP/SMTP. For the big providers it
detects the server settings automatically (built-in table, then the provider's
autoconfig, then Mozilla's ISPDB); you just supply a password. This page covers which
password each provider wants and what to do when something goes wrong.

Only statements we can verify are included; official help pages are linked where they
exist.

## Adding an account

Type your address and password in *Add account*. Dot Mail looks up the servers, tries a
test login (IMAP + SMTP), and only saves the account when both succeed. Detection order:
built-in table for the big providers, the provider's own autoconfig, your domain's MX
records (custom domains hosted at a known provider — e.g. Infomaniak or Google — are
recognised from their MX hosts, like Thunderbird does), then Mozilla's ISPDB. Any step
can be done manually — if detection fails or the login errors, the setup screen offers a
full manual form (IMAP/SMTP host, port, encryption, username).

### Gmail / googlemail

- IMAP is enabled by default in Gmail. If you ever switched it off, re-enable it:
  Gmail → gear icon → **See all settings** → **Forwarding and POP/IMAP** → *Enable IMAP*.
- **If your Google account has 2-Step Verification on, the regular password does not
  work for IMAP/SMTP — use an app password.** Create one at
  [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords)
  (or Google Account → Security → *App passwords*). You get a 16-character code; paste it
  into Dot Mail instead of your normal password.
  - 2-Step Verification must be active before app passwords exist: Google Account →
    Security → *2-Step Verification*.
  - App passwords are not available on accounts managed by an organisation (Workspace)
    unless the admin allows them, nor on Advanced Protection accounts.
- Google's own guide: <https://support.google.com/mail/answer/185833>.
- (OAuth2 sign-in is the long-term plan for Gmail, but app passwords work today.)

### iCloud (icloud.com, me.com, mac.com)

- Servers: `imap.mail.me.com` / `smtp.mail.me.com` (detected automatically).
- **Use an App-Specific Password**: create one at
  [appleid.apple.com](https://appleid.apple.com) → *Sign-In and Security* →
  *App-Specific Passwords* (you'll be asked for your Apple ID password, and a security
  code if two-factor authentication is on). Use that in Dot Mail, not your Apple ID
  password.
- Apple's mail-server reference: <https://support.apple.com/en-us/ht202304>.

### Yahoo and AOL

- **Use a third-party app password** — the regular Yahoo/AOL password no longer signs in
  to external mail apps: sign in at the
  [Yahoo Account Security page](https://info.yahoo.com/account/), go to
  *External connections* → *Create app password*, name it (e.g. "Dot Mail") and use the
  generated 16-character code in Dot Mail. Official guide:
  <https://help.yahoo.com/kb/SLN15241.html>.
- AOL.com accounts use the same Yahoo flow (AOL is managed through Yahoo Account
  Security).
- Some accounts temporarily cannot generate app passwords (Yahoo's eligibility checks).
  If the option is missing, try again from a browser you normally use; the official help
  page covers this.

### Infomaniak (ik.me, ikmail.com, etik.com, infomaniak.com, ik.io, and your own domain)

- Servers: `mail.infomaniak.com` — IMAP 993 SSL, SMTP 587 STARTTLS or 465 SSL
  ([official port guide](https://www.infomaniak.com/en/support/faq/468/understanding-mail-server-ports-and-protocols)).
- **Infomaniak does not accept your main account password for IMAP/SMTP.** Create a
  per-device password in the Manager: your Mail service → the address → *Manage
  password* → *Add a device* ([guide](https://www.infomaniak.com/en/support/faq/711/manage-devices-passwords-from-mail-infomaniak)).
  Use that generated password in Dot Mail.
- Your **own domain hosted on Infomaniak** is detected automatically: Dot Mail looks up
  the domain's MX records and recognises Infomaniak's MX hosts (the servers are still
  `mail.infomaniak.com`). Manual setup works as a fallback.

### Fastmail

- **Every IMAP/POP/SMTP connection needs an app password** — your normal Fastmail
  password is rejected by `imap.fastmail.com` on purpose. Create one: Fastmail →
  *Settings* → *Privacy & Security* → *Manage app passwords and access* →
  *New app password* (leave the default Mail scope). Official guides:
  <https://www.fastmail.help/hc/en-us/articles/360058752854-App-passwords> and
  <https://www.fastmail.help/hc/en-us/articles/1500000279921-IMAP-POP-and-SMTP>.
- Note: the *Basic* Fastmail plan does not include IMAP/SMTP access.

### mailbox.org and Posteo

- Both work with your **normal account password** (their autoconfig publishes plain
  password authentication).
- mailbox.org: `imap.mailbox.org` / `smtp.mailbox.org`, docs at
  <https://docs.mailbox.org/>.
- Posteo: the server is `posteo.de` itself (IMAP 993, SMTP 465/587); settings at
  <https://posteo.de/en/settings>.

### Outlook / Hotmail / Live / MSN (Microsoft personal accounts)

- **Not possible yet.** Microsoft disabled password sign-in for personal accounts, so
  these providers require OAuth2 (browser sign-in). Dot Mail detects them, explains this
  on the setup screen, and does not save the account. OAuth2 support is planned; until
  then, the workaround is an app password is **not** an option for personal Microsoft
  accounts — use a different provider for third-party clients, or wait.

### Proton

- **Not possible on Android.** Proton Mail has no public IMAP/SMTP; the Proton Mail
  Bridge that exposes it is desktop-only (Windows/macOS/Linux) and Proton does not
  support third-party mail clients on Android. Use the official Proton Mail app for
  those accounts. Dot Mail will tell you this at setup.

### Self-hosted / any other provider

- Add the account with the manual form: IMAP host/port + SMTP host/port, your mailbox
  username, and the password your server expects.
- **Encryption choices** (per server, in the manual form):
  - **SSL/TLS** — an encrypted connection from the first byte (classic ports 993/465).
    This is what Dot Mail guesses and what most servers offer.
  - **STARTTLS** — the connection starts unencrypted and upgrades to TLS after it opens
    (classic ports 143/587). Dot Mail *requires* the upgrade to actually happen; if the
    server refuses, the connection fails.
  - **None** — available for compatibility, and strongly discouraged: credentials and
    mail would travel in the clear.
- **Self-signed or internally-CA'd certificates are not supported yet.** Dot Mail
  verifies the server certificate and hostname strictly (it will refuse an untrusted
  certificate with a "certificate problem" error instead of offering to ignore it). If
  your server uses a certificate from a private CA, that's currently a blocker; we're
  aware of it and it's on the radar.
- Servers without IMAP (POP3-only setups) are not supported.

## Troubleshooting

### "Authentication failed" when adding an account

Check, in order:

1. The provider's password rules above — most failures are *regular password where an
   app password is required* (Gmail with 2SV, iCloud, Yahoo/AOL, Infomaniak, Fastmail).
2. Typos: app passwords are single-case codes with no spaces; paste, don't retype.
3. That the account actually has IMAP/SMTP enabled (Gmail's IMAP toggle, Fastmail's
   plan).
4. Your provider's own app (or webmail) still works — if it doesn't, the problem is
   with the account, not with Dot Mail.

### "Could not connect" / "certificate problem"

- *Could not connect*: the host or port is wrong (use the values above or your
  provider's docs), a firewall blocks 993/465, or the server is unreachable. If you are
  on a corporate network, IMAP/SMTP may be filtered.
- *Certificate problem*: the server's TLS certificate is not trusted by Android (self-
  signed, expired, or from an unknown CA). There is deliberately no "accept anyway"
  switch yet. Check the server configuration, or report it (below).

### Why are images blocked in HTML mails?

Remote images are a tracking mechanism: the sender's server learns the moment you open
the mail, from which IP, often before you tap anything. Dot Mail therefore **never
loads remote images by default** and strips their URLs from the rendered HTML. If you
want to see them for a specific message, tap *Load remote content* on that message (or
allow it globally in Settings). The same applies to other remote resources; inline
(`cid:`) images that belong to the mail itself always load.

### Why didn't I get a notification for a new mail?

A few facts, from the app's behaviour:

- Notifications are raised for **new, unread messages in the Inbox** only (other
  folders sync quietly).
- Sync happens on three triggers: a scheduled run **every ~15 minutes**, immediately
  when you open the app or pull to refresh, and **live (IMAP IDLE) only while the app
  is open in the foreground**. So with the app closed, a mail can appear up to ~15
  minutes late — that's by design (battery).
- Android can delay background work further: check that notifications for Dot Mail are
  **allowed** (Settings → Apps → Dot Mail → Notifications — on Android 13+ this is a
  permission; each account also has its own notification channel), and that battery
  optimisation isn't forcing the app into deep sleep.
- If you mark a mail read from another device before the sync runs, it won't notify
  (correct behaviour).

### Where is my data?

- Everything stays **on your device**: the local cache (folders, headers, read bodies,
  attachments) lives in Dot Mail's private app storage, in a local database
  (`dotmail.db`) plus attachment files.
- Your **password** is stored encrypted with an AES-GCM key that lives in the Android
  Keystore and never leaves the secure hardware; the encrypted blob is bound to your
  account address.
- The app is **excluded from Android cloud backup and device transfer** (`allowBackup`
  is off): restoring a phone does not copy your mail cache or credentials.
- Nothing of yours is sent anywhere except your mail server. The only other lookups are
  a DNS MX query for your domain (never your address) when adding an account on a
  custom domain, and, once, your domain (never your full address) sent to Mozilla's
  public ISPDB when Dot Mail needs server settings.

### How do I report a bug?

Open an issue at <https://github.com/Sr-0w/Dot-Mail/issues>. Useful details:

- provider + domain (or "manual settings" + the server, not the password), app version
  and Android version,
- what you expected vs. what happened, and whether it happens every time,
- screenshots are welcome — **never paste passwords, app passwords or tokens into an
  issue** (they would sit in the public tracker; rotate them if you already did).
