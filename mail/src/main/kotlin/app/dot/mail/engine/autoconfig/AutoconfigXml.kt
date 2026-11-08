package app.dot.mail.engine.autoconfig

import org.w3c.dom.Element
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/**
 * Servers parsed out of a Thunderbird autoconfig document, with placeholders
 * already substituted. Both [imap] and [smtp] are present.
 */
internal data class ParsedServers(
    val imap: DiscoveredServer,
    val smtp: DiscoveredServer,
    val providerName: String?,
)

/**
 * Parses Thunderbird autoconfig documents (config-v1.1). The same schema is used by
 * provider autoconfig (`https://autoconfig.<domain>/...`) and by the Mozilla ISPDB
 * (`https://autoconfig.thunderbird.net/v1.1/<domain>`).
 *
 * Selection rules, per the autoconfig spec and our product policy:
 *  - incoming: prefer `type="imap"`; use `type="pop3"` (or an untyped block) only when
 *    no imap block exists;
 *  - outgoing: prefer `type="smtp"`;
 *  - within a type, prefer socketType SSL over STARTTLS over PLAIN;
 *  - `%EMAILADDRESS%`, `%EMAILLOCALPART%`, `%EMAILDOMAIN%` are substituted.
 *
 * Security: the document is parsed with a hardened [DocumentBuilderFactory] —
 * DOCTYPE declarations are disallowed entirely and external entities are off — so
 * XXE payloads are rejected (the document is simply unusable) instead of expanded.
 *
 * Returns null when the document is missing, malformed, or has no usable server.
 */
internal object AutoconfigXml {

    private val socketRank = mapOf(
        SocketType.SSL to 3,
        SocketType.STARTTLS to 2,
        SocketType.PLAIN to 1,
    )

    fun parse(xml: String, email: String, localPart: String, domain: String): ParsedServers? {
        val root = safeParse(xml) ?: return null

        val imapEl = pick(root, "incomingServer", mapOf("imap" to 3, "pop3" to 1)) ?: return null
        val smtpEl = pick(root, "outgoingServer", mapOf("smtp" to 3)) ?: return null

        val imap = toServer(imapEl, email, localPart, domain, defaultPort = { s ->
            when (s) { SocketType.SSL -> 993; else -> 143 }
        }) ?: return null
        val smtp = toServer(smtpEl, email, localPart, domain, defaultPort = { s ->
            when (s) { SocketType.SSL -> 465; SocketType.STARTTLS -> 587; else -> 25 }
        }) ?: return null

        // displayName sits inside <emailProvider>/<ISP>, not directly under <clientInfo>.
        return ParsedServers(imap, smtp, root.findTextAnywhere("displayName"))
    }

    /** Text of the first descendant element named [name] with non-blank content. */
    private fun org.w3c.dom.Element.findTextAnywhere(name: String): String? {
        val nodes = getElementsByTagName(name)
        for (i in 0 until nodes.length) {
            val text = nodes.item(i).textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    /**
     * Picks the best candidate <[tag]> element: highest (type score + socket rank),
     * first one wins ties. Untyped/unknown-type blocks score 1 so they are only
     * used when nothing better exists.
     */
    private fun pick(root: Element, tag: String, typeScores: Map<String, Int>): Element? {
        val nodes = root.getElementsByTagName(tag)
        var best: Element? = null
        var bestScore = 0
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val type = el.getAttribute("type").trim().lowercase()
            val typeScore = typeScores[type] ?: if (type.isEmpty()) 1 else 0
            if (typeScore <= 0) continue
            if (el.childText("hostname").orEmpty().isBlank()) continue
            val score = typeScore + socketRank[parseSocket(el.childText("socketType"))]!!
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        return best
    }

    private fun toServer(
        el: Element,
        email: String,
        localPart: String,
        domain: String,
        defaultPort: (SocketType) -> Int,
    ): DiscoveredServer? {
        val host = el.childText("hostname")?.let { substitute(it, email, localPart, domain) } ?: return null
        if (host.isBlank()) return null
        val socket = parseSocket(el.childText("socketType"))
        val port = el.childText("port")?.toIntOrNull()
            ?: defaultPort(socket)
        if (port !in 1..65535) return null
        val username = el.childText("username")?.takeIf { it.isNotBlank() }
            ?: email
        return DiscoveredServer(host, port, socket, substitute(username, email, localPart, domain))
    }

    private fun parseSocket(value: String?): SocketType = when {
        value == null -> SocketType.SSL // spec default when the element is absent
        value.isBlank() -> SocketType.SSL
        value.lowercase() in setOf("ssl", "tls") -> SocketType.SSL
        value.lowercase() in setOf("starttls", "imap-tls", "smtp-tls", "ssl-starttls") -> SocketType.STARTTLS
        else -> SocketType.PLAIN // "plain", "notls", "none", unknown
    }

    private fun substitute(value: String, email: String, localPart: String, domain: String): String =
        value
            .replace("%EMAILADDRESS%", email)
            .replace("%EMAILLOCALPART%", localPart)
            .replace("%EMAILDOMAIN%", domain)

    /** Text of the first *direct* child element named [name], trimmed. */
    private fun Element.childText(name: String): String? {
        val nodes = getElementsByTagName(name)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.parentNode === this) {
                val text = node.textContent?.trim()
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }

    /**
     * Parse [xml] with an XXE-safe factory. Any failure (malformed XML, DOCTYPE
     * declaration, external entity access, ...) yields null rather than an exception.
     */
    private fun safeParse(xml: String): Element? {
        if (xml.isBlank()) return null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            setQuietly { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            setQuietly { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            setQuietly { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            setQuietly { setFeature("http://javax.xml.XMLReader/feature/secure-processing", true) }
        }
        val builder = try {
            factory.newDocumentBuilder()
        } catch (e: ParserConfigurationException) {
            return null
        }
        val doc = try {
            builder.parse(InputSource(StringReader(xml)))
        } catch (e: SAXException) {
            return null // includes DOCTYPE rejection (XXE) and malformed input
        } catch (e: IOException) {
            return null
        }
        return doc.documentElement
    }

    private inline fun DocumentBuilderFactory.setQuietly(block: DocumentBuilderFactory.() -> Unit) {
        try {
            block()
        } catch (e: ParserConfigurationException) {
            // Parser does not support this feature; the others still apply.
        }
    }
}
