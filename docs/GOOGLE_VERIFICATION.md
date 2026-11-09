# Google OAuth verification (Gmail, restricted scope)

Dot Mail asks for `https://mail.google.com/`, the only Google scope that IMAP and SMTP (XOAUTH2) accept. It is a
**restricted** scope, so leaving *Testing* mode needs Google's OAuth app verification. Apps that keep Gmail data on
the device and never send it to their own servers normally don't need the paid CASA security assessment; Google
decides this during review. See Google's
[restricted-scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification).

Who does what: the maintainer (or Ari, with the maintainer's Google account) does every step in Google Cloud and
Search Console. Nothing here needs code changes.

## 1. Public website on a domain the maintainer owns

Google requires a homepage and a privacy policy on a verified domain, linked from each other.

- Pages to publish: [`site/index.html`](../site/index.html) (homepage) and [`site/privacy.html`](../site/privacy.html)
  (privacy policy). They are self-contained (no external resources).
- Host them on a domain the maintainer controls, for example `dotmail.snyders.xyz` (`/` and `/privacy.html`).
- Verify that domain in [Google Search Console](https://search.google.com/search-console) (DNS TXT record) with the same
  Google account that owns the Cloud project.

## 2. OAuth consent screen (Google Cloud → Google Auth Platform)

- **Branding:** app name `Dot Mail` (must match the app and the website), logo = the app icon (120×120 PNG), support
  e-mail, homepage URL, privacy policy URL, authorized domain = the verified domain from step 1, developer contact.
- **Audience:** External, publishing status → **In production** (this starts verification).
- **Data access:** only `https://mail.google.com/`. Remove every other scope; `openid`/`email`/`profile` are not
  requested by the app.

## 3. Scope justification (paste into the form)

> Dot Mail is an open-source Android email client (IMAP/SMTP). Users who choose "Continue with Google" sign in with
> OAuth so that the app can connect to Gmail's IMAP and SMTP servers via XOAUTH2. The scope https://mail.google.com/ is
> the only scope Google accepts for IMAP and SMTP access; narrower Gmail API scopes cannot be used with these
> protocols. The app uses it to display, search, organise (archive, label/folder moves, flags, delete) and send the
> user's email, entirely on the device. Gmail data is stored only in the app's private storage on the phone and is
> transmitted only between the phone and Google's servers. The developer operates no server and never receives,
> stores, shares or sells Google user data; it is not used for advertising or to train AI/ML models. Tokens are
> encrypted with the Android Keystore. The source code is public: https://github.com/Sr-0w/Dot-Mail

## 4. Demo video (unlisted YouTube link)

Record on the phone, English UI (Android language set to English), about 2–3 minutes, with a **test** Gmail account.
Show:

1. The app opening on *Add account* → **Continue with Google**.
2. The Google consent screen in the system browser, with the **address bar expanded so the OAuth URL and its
   `client_id` are readable** (Google checks it matches the project), and the requested permission.
3. Granting access and returning to the app; the inbox syncing.
4. Each use of the scope: open and read a message, search, archive or move a message, delete one, compose and send a
   message, and show it in Sent.
5. Settings → account → *Remove account*, and the access listed/revoked at myaccount.google.com/permissions.

## 5. After submission

Google replies by e-mail to the developer contact, usually within days to a few weeks, and may ask follow-up questions
(often about the scope or the video). Until approval the consent screen stays in Testing: listed test users only,
grants expire after 7 days.

## Release builds

Nothing changes in the app: the client IDs stay as documented in [OAUTH.md](OAUTH.md). If the release signing key
changes, the SHA-1 in the Android OAuth client must be updated before the release.
