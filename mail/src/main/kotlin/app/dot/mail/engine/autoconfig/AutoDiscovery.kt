package app.dot.mail.engine.autoconfig

import java.net.IDN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A syntactically valid email address, split and normalised. */
internal data class ParsedEmail(val localPart: String, val domain: String, val full: String)

/**
 * Domain labels: `label(.label)*` where a label is `[a-z0-9]` plus inner
 * `[a-z0-9-]`, not starting/ending with `-`. Checked after IDN (punycode)
 * normalisation, so it only ever sees ASCII.
 */
private val DOMAIN_RE = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*")

/**
 * Splits [email] into local part and domain.
 *
 * - trims surrounding whitespace;
 * - keeps the local part case-sensitive, lowercases the domain;
 * - normalises the domain to ASCII via [IDN.toASCII] (punycode for IDN domains);
 * - returns null for malformed input (missing/empty part, whitespace, extra `@`,
 *   or an invalid domain shape).
 */
internal fun parseEmail(email: String): ParsedEmail? {
    val trimmed = email.trim()
    if (trimmed.isEmpty()) return null
    val at = trimmed.lastIndexOf('@')
    if (at <= 0 || at == trimmed.length - 1) return null
    val local = trimmed.substring(0, at)
    val domainPart = trimmed.substring(at + 1)
    if (local.isBlank() || domainPart.isBlank()) return null
    if (local.any { it.isWhitespace() || it.isISOControl() || it == '@' }) return null
    if (domainPart.any { it.isWhitespace() || it.isISOControl() }) return null
    val domainLower = domainPart.lowercase()
    val domain = try {
        IDN.toASCII(domainLower, IDN.ALLOW_UNASSIGNED)
    } catch (e: IllegalArgumentException) {
        return null
    }
    if (!domain.matches(DOMAIN_RE)) return null
    return ParsedEmail(local, domain, "$local@$domain")
}

/**
 * Discovers IMAP/SMTP configuration for [email].
 *
 * Order of precedence:
 *  1. built-in table of well-known providers ([Source.BUILT_IN]) — no network;
 *  2. provider autoconfig ([Source.PROVIDER_AUTOCONFIG]):
 *     `https://autoconfig.<domain>/mail/config-v1.1.xml`, then
 *     `https://<domain>/.well-known/autoconfig/mail/config-v1.1.xml`;
 *  3. MX lookup ([Source.MX]), like Thunderbird: the domain's MX hosts are matched against
 *     well-known providers (custom domains hosted at Infomaniak, Google, Microsoft 365, ...),
 *     else ISPDB is queried with the MX host's registrable domain;
 *  4. Mozilla ISPDB ([Source.ISPDB]): `https://autoconfig.thunderbird.net/v1.1/<domain>`;
 *  5. educated guess ([Source.GUESS]): `imap.<domain>:993` SSL, `smtp.<domain>:465` SSL.
 *
 * Privacy: every URL contains only the domain — never the full email address.
 *
 * Network I/O happens through [fetcher] (an [HttpsConfigFetcher] by default) and
 * the whole discovery runs on [Dispatchers.IO]. Malformed emails and documents that
 * are unusable for any source are handled by falling back; a syntactically invalid
 * email yields null without touching the network.
 */
/** Resolves the MX hosts of [domain], best (lowest preference) first. Blocking; empty when unknown/unavailable. */
fun interface MxResolver {
    fun lookup(domain: String): List<String>
}

class AutoDiscovery(
    private val fetcher: ConfigFetcher = HttpsConfigFetcher(),
    private val mxResolver: MxResolver = MxResolver { emptyList() },
) {

    suspend fun discover(email: String): DiscoveredConfig? = withContext(Dispatchers.IO) {
        val parsed = parseEmail(email) ?: return@withContext null
        val local = parsed.localPart
        val domain = parsed.domain
        val full = parsed.full

        BuiltInProviders.lookup(domain)?.let { return@withContext it.toConfig(local, full) }

        for (url in listOf(
            "https://autoconfig.$domain/mail/config-v1.1.xml",
            "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml",
        )) {
            fetchConfig(url, full, local, domain, Source.PROVIDER_AUTOCONFIG)?.let { return@withContext it }
        }
        discoverViaMx(domain, local, full)?.let { return@withContext it }

        fetchConfig("https://autoconfig.thunderbird.net/v1.1/$domain", full, local, domain, Source.ISPDB)
            ?.let { return@withContext it }

        return@withContext guess(full, domain)
    }

    private fun discoverViaMx(domain: String, local: String, email: String): DiscoveredConfig? {
        val hosts = try {
            mxResolver.lookup(domain)
        } catch (e: Exception) {
            return null // DNS trouble must not abort discovery
        }.map { it.trim().trimEnd('.').lowercase() }.filter { it.isNotEmpty() }
        if (hosts.isEmpty()) return null
        for (host in hosts) {
            BuiltInProviders.lookupByMx(host)?.let { return it.toConfig(local, email, Source.MX) }
        }
        for (base in hosts.map(::registrableDomain).distinct().filter { it != domain }.take(3)) {
            fetchConfig("https://autoconfig.thunderbird.net/v1.1/$base", email, local, domain, Source.MX)
                ?.let { return it }
        }
        return null
    }

    private fun BuiltInProviders.Entry.toConfig(local: String, email: String, source: Source = Source.BUILT_IN): DiscoveredConfig {
        val username = if (usernameIsFullEmail) email else local
        return DiscoveredConfig(
            imap = DiscoveredServer(imapHost, imapPort, imapSocket, username),
            smtp = DiscoveredServer(smtpHost, smtpPort, smtpSocket, username),
            providerName = providerName,
            hint = hint,
            oauthRequired = oauthRequired,
            source = source,
        )
    }

    private fun fetchConfig(
        url: String,
        email: String,
        localPart: String,
        domain: String,
        source: Source,
    ): DiscoveredConfig? {
        val body = try {
            fetcher.fetch(url)
        } catch (e: Exception) {
            // A broken/strict fetcher must not abort discovery: treat as "not found".
            return null
        } ?: return null
        val servers = AutoconfigXml.parse(body, email, localPart, domain) ?: return null
        return DiscoveredConfig(
            imap = servers.imap,
            smtp = servers.smtp,
            providerName = servers.providerName,
            hint = null,
            oauthRequired = false,
            source = source,
        )
    }

    private fun guess(email: String, domain: String): DiscoveredConfig = DiscoveredConfig(
        imap = DiscoveredServer("imap.$domain", 993, SocketType.SSL, email),
        smtp = DiscoveredServer("smtp.$domain", 465, SocketType.SSL, email),
        providerName = null,
        hint = null,
        oauthRequired = false,
        source = Source.GUESS,
    )
}

private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac", "ne", "or", "go")

/**
 * Approximate registrable domain ("eTLD+1") without a public-suffix list: last two labels, or
 * last three for the common `co.uk` / `com.au` shape. Good enough to address ISPDB.
 */
internal fun registrableDomain(host: String): String {
    val labels = host.trim().trimEnd('.').lowercase().split('.').filter { it.isNotEmpty() }
    if (labels.size <= 2) return labels.joinToString(".")
    val n = if (labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL) 3 else 2
    return labels.takeLast(n).joinToString(".")
}
