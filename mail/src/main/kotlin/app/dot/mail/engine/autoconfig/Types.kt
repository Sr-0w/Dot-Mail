package app.dot.mail.engine.autoconfig

/** Transport security used for the socket. */
enum class SocketType { SSL, STARTTLS, PLAIN }

/** Where a [DiscoveredConfig] came from, in order of trust. */
enum class Source { BUILT_IN, PROVIDER_AUTOCONFIG, ISPDB, GUESS }

/**
 * One server (IMAP or SMTP) with every field resolved and ready to use:
 * no placeholders, no unresolved usernames.
 */
data class DiscoveredServer(
    val host: String,
    val port: Int,
    val socket: SocketType,
    /** Username already resolved for the email. */
    val username: String,
)

/** Complete, ready-to-use account configuration for a single email address. */
data class DiscoveredConfig(
    val imap: DiscoveredServer,
    val smtp: DiscoveredServer,
    /** Human-readable provider name (e.g. "Gmail"), null when unknown. */
    val providerName: String?,
    /**
     * Short, actionable hint for the user (e.g. "use an app password").
     * Shown on the setup screen; null when there is nothing to say.
     */
    val hint: String?,
    /** True when password sign-in is not accepted at all and OAuth2 is mandatory. */
    val oauthRequired: Boolean,
    val source: Source,
)
