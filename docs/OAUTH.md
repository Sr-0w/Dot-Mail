# OAuth2 sign-in (Microsoft, Google)

Dot Mail already contains the complete OAuth2 flow: PKCE, the system browser (no embedded WebView, no Google Play
Services), a strict redirect check, encrypted token storage, token refresh, and XOAUTH2 for IMAP and SMTP. What a
build needs is a **client ID** registered by the project with each provider. Nobody else's client ID may be reused.

Without client IDs, the build still works: Outlook/Hotmail accounts are detected and explained on the setup screen,
and Gmail works with an app password.

Client IDs are not secrets for a public mobile client (PKCE, no client secret), but they identify the publisher, so
they are passed at build time instead of being committed:

```
# ~/.gradle/gradle.properties (or -P on the command line / CI secret)
dotmail.oauth.microsoft.clientId=00000000-0000-0000-0000-000000000000
dotmail.oauth.google.clientId=1234567890-abc.apps.googleusercontent.com
```

## Microsoft (Outlook.com, Hotmail, Microsoft 365): needed, ~10 minutes, free

Microsoft no longer accepts passwords for IMAP on personal accounts, so this is what makes Outlook/Hotmail work.

1. Sign in at <https://entra.microsoft.com> → **App registrations** → **New registration**.
2. Name `Dot Mail`. Supported account types: **Accounts in any organizational directory and personal Microsoft
   accounts**.
3. Redirect URI: platform **Public client/native (mobile & desktop)**, value `app.dot.mail:/oauth2redirect`.
4. Register, then copy the **Application (client) ID** into `dotmail.oauth.microsoft.clientId`.
5. **Authentication** → *Allow public client flows*: **Yes**.
6. **API permissions** → Add → *APIs my organization uses* → `Office 365 Exchange Online` → delegated
   `IMAP.AccessAsUser.All` and `SMTP.Send`; also Microsoft Graph `offline_access`. No admin consent is needed
   for personal accounts.
7. Optional but recommended before a public release: **Branding & properties** → publisher verification
   (blue check in the consent screen).

## Google (Gmail): optional

Gmail keeps working with app passwords (Google account → Security → 2-Step Verification → App passwords). Full
mail access (`https://mail.google.com/`) is a *restricted* scope: a client ID works immediately for up to 100 test
users, but a public release needs Google's app verification **and a yearly third-party security assessment
(CASA)**. That is a cost/policy decision for the maintainer, not a code task.

1. <https://console.cloud.google.com> → new project `Dot Mail` → **APIs & Services** → enable **Gmail API**.
2. **OAuth consent screen**: External, app name, support e-mail, privacy policy URL (docs/FAQ.md "Privacy" section
   can be published as a page), scope `https://mail.google.com/`, add test users.
3. **Credentials** → Create OAuth client ID → **Android**, package `app.dot.mail`, SHA-1 of the release key
   (`keytool -list -v -keystore release.jks`). Use the **iOS** type instead if Android-type verification of the
   custom scheme causes trouble; both are public clients.
4. Copy the client ID into `dotmail.oauth.google.clientId`. The redirect scheme
   (`com.googleusercontent.apps.<id>:/oauth2redirect`) is derived automatically at build time.

## Release builds

`scripts/release.sh` builds from a fresh clone of the tag on the build host; put the two properties in the build
host's `~/.gradle/gradle.properties`. Reproducible-build checks (`scripts/reprotest.sh`) need the same values, since
they end up in `BuildConfig`. F-Droid builds without them unless the maintainer gives F-Droid the IDs: that's fine,
and the app then falls back to passwords and app passwords as described above.
