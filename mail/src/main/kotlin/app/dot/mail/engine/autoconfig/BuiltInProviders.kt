package app.dot.mail.engine.autoconfig

/**
 * Built-in table for well-known providers.
 *
 * These entries are used as the *first* discovery step (no network), keyed by the
 * lowercased, IDN-normalised (ASCII/punycode) domain. Every host/port combination
 * below was verified against the Mozilla ISPDB
 * (https://autoconfig.thunderbird.net/v1.1/<domain>), the provider's own
 * autoconfig file, or official provider documentation (checked 2026-11-08).
 * SSL (implicit TLS) on 993/465 is preferred where the provider documents it.
 *
 * Domains we are not certain about are deliberately left out: they still resolve
 * via provider autoconfig / ISPDB / guess, which is the correct fallback.
 */
internal object BuiltInProviders {

    /** A single built-in provider definition. */
    internal data class Entry(
        val providerName: String,
        val imapHost: String,
        val imapPort: Int,
        val imapSocket: SocketType,
        val smtpHost: String,
        val smtpPort: Int,
        val smtpSocket: SocketType,
        val oauthRequired: Boolean = false,
        val hint: String? = null,
        /** False when the provider wants only the local part as username (web.de). */
        val usernameIsFullEmail: Boolean = true,
    )

    private val byDomain: Map<String, Entry> = buildMap {
        // --- Google / Gmail -----------------------------------------------------
        // ISPDB: imap.gmail.com:993 SSL, smtp.gmail.com:465 SSL.
        val gmail = Entry(
            providerName = "Gmail",
            imapHost = "imap.gmail.com", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "smtp.gmail.com", smtpPort = 465, smtpSocket = SocketType.SSL,
            hint = "Gmail: use an app password (Google Account → Security → App passwords).",
        )
        listOf("gmail.com", "googlemail.com", "googlemail.de", "googlemail.co.uk").forEach { put(it, gmail) }

        // --- Microsoft / Outlook / Hotmail / Live / MSN -------------------------
        // Microsoft disabled basic (password) auth for personal accounts; OAuth2 is
        // required (ISPDB marks both servers OAuth2). Domain list per ISPDB.
        val outlook = Entry(
            providerName = "Outlook",
            imapHost = "outlook.office365.com", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "smtp-mail.outlook.com", smtpPort = 587, smtpSocket = SocketType.STARTTLS,
            oauthRequired = true,
            hint = "Outlook/Hotmail/Live/MSN: Microsoft disabled password sign-in for personal accounts — use OAuth2.",
        )
        listOf(
            "outlook.com", "windowslive.com",
            "outlook.at", "outlook.be", "outlook.cl", "outlook.cz", "outlook.de", "outlook.dk",
            "outlook.es", "outlook.fr", "outlook.hu", "outlook.ie", "outlook.in", "outlook.it",
            "outlook.jp", "outlook.kr", "outlook.lv", "outlook.my", "outlook.ph", "outlook.pt",
            "outlook.sa", "outlook.sg", "outlook.sk", "outlook.co.id", "outlook.co.il",
            "outlook.co.th", "outlook.com.ar", "outlook.com.au", "outlook.com.br",
            "outlook.com.gr", "outlook.com.tr", "outlook.com.vn",
            "hotmail.com",
            "hotmail.be", "hotmail.ca", "hotmail.cl", "hotmail.cz", "hotmail.de", "hotmail.dk",
            "hotmail.es", "hotmail.fi", "hotmail.fr", "hotmail.gr", "hotmail.hu", "hotmail.it",
            "hotmail.lt", "hotmail.lv", "hotmail.my", "hotmail.nl", "hotmail.no", "hotmail.ph",
            "hotmail.rs", "hotmail.se", "hotmail.sg", "hotmail.sk", "hotmail.co.id",
            "hotmail.co.il", "hotmail.co.in", "hotmail.co.jp", "hotmail.co.kr", "hotmail.co.th",
            "hotmail.co.uk", "hotmail.co.za", "hotmail.com.ar", "hotmail.com.au",
            "hotmail.com.br", "hotmail.com.hk", "hotmail.com.tr", "hotmail.com.tw",
            "hotmail.com.vn",
            "live.com",
            "live.at", "live.be", "live.ca", "live.cl", "live.cn", "live.de", "live.dk",
            "live.fi", "live.fr", "live.hk", "live.ie", "live.in", "live.it", "live.jp",
            "live.nl", "live.no", "live.ru", "live.se", "live.co.jp", "live.co.kr",
            "live.co.uk", "live.co.za", "live.com.ar", "live.com.au", "live.com.mx",
            "live.com.my", "live.com.ph", "live.com.pt", "live.com.sg",
            "livemail.tw",
            "msn.com",
        ).forEach { put(it, outlook) }

        // --- Yahoo ----------------------------------------------------------------
        // ISPDB: imap.mail.yahoo.com:993 SSL, smtp.mail.yahoo.com:465 SSL.
        val yahoo = Entry(
            providerName = "Yahoo",
            imapHost = "imap.mail.yahoo.com", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "smtp.mail.yahoo.com", smtpPort = 465, smtpSocket = SocketType.SSL,
            hint = "Yahoo: use an app password (Yahoo Account → Account security → App passwords).",
        )
        listOf(
            "yahoo.com", "yahoo.ca", "yahoo.de", "yahoo.it", "yahoo.fr", "yahoo.es", "yahoo.se",
            "yahoo.co.in", "yahoo.co.nz", "yahoo.com.au", "yahoo.com.ar", "yahoo.com.br",
            "yahoo.com.mx", "ymail.com", "myyahoo.com", "rocketmail.com", "cox.net",
        ).forEach { put(it, yahoo) }

        // --- Apple / iCloud ---------------------------------------------------------
        // Apple support (HT202304): imap.mail.me.com 993 SSL; smtp.mail.me.com 465 SSL.
        val icloud = Entry(
            providerName = "iCloud",
            imapHost = "imap.mail.me.com", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "smtp.mail.me.com", smtpPort = 465, smtpSocket = SocketType.SSL,
            hint = "iCloud: use an App-Specific Password (appleid.apple.com → Sign-In & Security → App-Specific Passwords).",
        )
        listOf("icloud.com", "me.com", "mac.com").forEach { put(it, icloud) }

        // --- Infomaniak ---------------------------------------------------------------
        // Official Infomaniak FAQ (faq.infomaniak.com/468): server mail.infomaniak.com,
        // IMAP 993 SSL, SMTP 465 SSL (implicit) or 587 STARTTLS.
        val infomaniak = Entry(
            providerName = "Infomaniak",
            imapHost = "mail.infomaniak.com", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "mail.infomaniak.com", smtpPort = 465, smtpSocket = SocketType.SSL,
        )
        listOf("infomaniak.com", "ik.me", "ikmail.com", "etik.com", "ik.io").forEach { put(it, infomaniak) }

        // --- Fastmail -----------------------------------------------------------------
        // Fastmail's own autoconfig (autoconfig.fastmail.com): imap.fastmail.com:993 SSL,
        // smtp.fastmail.com:465 SSL.
        val fastmail = Entry(
            providerName = "Fastmail",
            imapHost = "imap.fastmail.com", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "smtp.fastmail.com", smtpPort = 465, smtpSocket = SocketType.SSL,
        )
        listOf("fastmail.com", "fastmail.co.uk", "fastmail.eu", "fastmail.net",
            "fastmail.com.au", "fastmail.co.nz").forEach { put(it, fastmail) }

        // --- GMX (Europe: .net/.de servers) -----------------------------------------------
        // ISPDB: imap.gmx.net:993 SSL, smtp mail.gmx.net:465 SSL.
        val gmxEurope = Entry(
            providerName = "GMX",
            imapHost = "imap.gmx.net", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "mail.gmx.net", smtpPort = 465, smtpSocket = SocketType.SSL,
        )
        listOf("gmx.net", "gmx.de").forEach { put(it, gmxEurope) }

        // --- GMX (US/international: .com servers) ------------------------------------------
        // ISPDB: separate access servers for gmx.com.
        val gmxUs = Entry(
            providerName = "GMX",
            imapHost = "imap.gmx.com", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "mail.gmx.com", smtpPort = 465, smtpSocket = SocketType.SSL,
        )
        put("gmx.com", gmxUs)

        // --- web.de -------------------------------------------------------------------------
        // ISPDB: imap.web.de:993 SSL, smtp.web.de:465 SSL; username is the LOCAL PART.
        put("web.de", Entry(
            providerName = "web.de",
            imapHost = "imap.web.de", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "smtp.web.de", smtpPort = 465, smtpSocket = SocketType.SSL,
            usernameIsFullEmail = false,
        ))

        // --- mailbox.org -----------------------------------------------------------------------
        // mailbox.org's own autoconfig: imap.mailbox.org:993 SSL, smtp.mailbox.org:465 SSL.
        put("mailbox.org", Entry(
            providerName = "mailbox.org",
            imapHost = "imap.mailbox.org", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "smtp.mailbox.org", smtpPort = 465, smtpSocket = SocketType.SSL,
        ))

        // --- Posteo -------------------------------------------------------------------------------
        // ISPDB: the domain itself is the mail server — posteo.de:993 SSL / posteo.de:465 SSL.
        // Domain list per ISPDB (Posteo sells these TLDs for its users).
        val posteo = Entry(
            providerName = "Posteo",
            imapHost = "posteo.de", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "posteo.de", smtpPort = 465, smtpSocket = SocketType.SSL,
        )
        listOf(
            "posteo.de", "posteo.at", "posteo.be", "posteo.ca", "posteo.ch", "posteo.cl",
            "posteo.co", "posteo.co.uk", "posteo.com", "posteo.com.br", "posteo.cr",
            "posteo.cz", "posteo.dk", "posteo.ee", "posteo.es", "posteo.eu", "posteo.fi",
            "posteo.gl", "posteo.gr", "posteo.hn", "posteo.hr", "posteo.hu", "posteo.ie",
            "posteo.in", "posteo.is", "posteo.it", "posteo.jp", "posteo.la", "posteo.li",
            "posteo.lt", "posteo.lu", "posteo.me", "posteo.mx", "posteo.my", "posteo.net",
            "posteo.nl", "posteo.no", "posteo.nz", "posteo.org", "posteo.pe", "posteo.pl",
            "posteo.pm", "posteo.pt", "posteo.ro", "posteo.se", "posteo.sg", "posteo.si",
            "posteo.tn", "posteo.uk", "posteo.us",
        ).forEach { put(it, posteo) }

        // --- Zoho (US servers) ----------------------------------------------------------------------
        // ISPDB: imap.zoho.com:993 SSL, smtp.zoho.com:465 SSL.
        val zohoUs = Entry(
            providerName = "Zoho",
            imapHost = "imap.zoho.com", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "smtp.zoho.com", smtpPort = 465, smtpSocket = SocketType.SSL,
        )
        listOf("zoho.com", "zohomail.com").forEach { put(it, zohoUs) }

        // --- Zoho (Europe servers) ---------------------------------------------------------------------
        // ISPDB: imap.zoho.eu:993 SSL, smtp.zoho.eu:465 SSL.
        val zohoEu = Entry(
            providerName = "Zoho",
            imapHost = "imap.zoho.eu", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "smtp.zoho.eu", smtpPort = 465, smtpSocket = SocketType.SSL,
        )
        listOf("zoho.eu", "zohomail.eu").forEach { put(it, zohoEu) }

        // --- AOL ------------------------------------------------------------------------------------------
        // ISPDB: imap.aol.com:993 SSL, smtp.aol.com:465 SSL. Domain list per ISPDB.
        val aol = Entry(
            providerName = "AOL",
            imapHost = "imap.aol.com", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "smtp.aol.com", smtpPort = 465, smtpSocket = SocketType.SSL,
        )
        listOf(
            "aol.com", "aim.com", "netscape.net", "netscape.com", "compuserve.com", "cs.com",
            "wmconnect.com", "aol.de", "aol.it", "aol.fr", "aol.es", "aol.se", "aol.co.uk",
            "aol.co.nz", "aol.com.au", "aol.com.ar", "aol.com.br", "aol.com.mx",
        ).forEach { put(it, aol) }

        // --- Proton -----------------------------------------------------------------------------------------
        // Proton Mail has no public IMAP/SMTP. Proton Mail Bridge is desktop-only
        // (Windows/macOS/Linux) and is not available on Android, so no localhost config
        // can work here. We surface the provider with oauthRequired=true — the same flag
        // the UI uses to mean "password sign-in will not work" — plus a clear hint.
        val proton = Entry(
            providerName = "Proton",
            imapHost = "mail.proton.me", imapPort = 993, imapSocket = SocketType.SSL,
            smtpHost = "mail.proton.me", smtpPort = 465, smtpSocket = SocketType.SSL,
            oauthRequired = true,
            hint = "Proton Mail doesn't offer IMAP access on Android (Proton Mail Bridge is desktop-only). Use the Proton Mail app instead.",
        )
        listOf("proton.me", "protonmail.com", "pm.me").forEach { put(it, proton) }
    }

    /** Look up a provider by its (already normalised) domain. Returns null when unknown. */
    fun lookup(domain: String): Entry? = byDomain[domain]
}
