package app.dot.mail.data

import android.content.Context
import android.security.KeyChain
import app.dot.mail.data.model.SmimeAccountConfig
import app.dot.mail.engine.SmimeSigningIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** What Settings shows about a chosen KeyChain entry. */
sealed interface SmimeKeyStatus {
    /** No alias chosen. */
    data object NotSet : SmimeKeyStatus

    /** An alias is stored but KeyChain no longer yields its key or certificate (removed, or access was revoked): pick again. */
    data object Unavailable : SmimeKeyStatus

    class Available(val subject: String, val issuer: String, val notBefore: Long, val notAfter: Long, val mailboxes: Set<String>) : SmimeKeyStatus {
        fun validity(now: Long): SmimeCertValidity = when { now < notBefore -> SmimeCertValidity.NOT_YET_VALID; now > notAfter -> SmimeCertValidity.EXPIRED; else -> SmimeCertValidity.VALID }
        /** Whether the certificate names [email] (S/MIME signatures are only meaningful for the sender's own address). */
        fun namesMailbox(email: String) = SmimeCertStore.normalize(email) in mailboxes
    }
}

/** A short readable name for a certificate subject: its CN, else the whole distinguished name. */
fun smimeSubjectName(principal: X500Principal): String = smimeSubjectNameFromDn(principal.getName(X500Principal.RFC2253))

/** Same, from an RFC 2253 distinguished name as stored with a certificate. */
fun smimeSubjectNameFromDn(dn: String): String =
    Regex("(?i)(?:^|,)CN=([^,]+)").find(dn)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: dn

/**
 * [SmimeKeyAccess] on Android KeyChain, resolving the account's stored aliases at the moment they are needed. A key that is
 * gone or no longer granted is "unavailable, pick again": never an empty result, never a crash, never an unprotected send.
 * KeyChain blocks and refuses to run on the main thread, so everything here runs on the IO dispatcher.
 */
class KeyChainSmimeKeys(
    private val context: Context,
    private val config: suspend (accountId: Long) -> SmimeAccountConfig,
    private val chainOf: (Context, String) -> Array<X509Certificate>? = { c, alias -> KeyChain.getCertificateChain(c, alias) },
    private val keyOf: (Context, String) -> PrivateKey? = { c, alias -> KeyChain.getPrivateKey(c, alias) },
) : SmimeKeyAccess {

    override suspend fun signingIdentity(accountId: Long): SmimeSigningIdentity {
        val settings = config(accountId)
        val alias = settings.signingKeyChainAlias ?: throw notConfigured()
        val (chain, key) = load(alias)
        // Advertise the decryption certificate when it is a separate key; a missing one must not block signing.
        val encryption = settings.decryptionKeyChainAlias?.takeIf { it != alias }?.let { other ->
            try { withContext(Dispatchers.IO) { chainOf(context, other)?.firstOrNull() } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        } ?: chain.first()
        return SmimeSigningIdentity(chain.first(), key, chain.toList(), encryption)
    }

    override suspend fun decryptionCertificate(accountId: Long): X509Certificate {
        val alias = config(accountId).decryptionKeyChainAlias ?: throw notConfigured()
        return load(alias).first.first()
    }

    /** The decryption identity (certificate and private key) for reading received mail. */
    suspend fun decryptionIdentity(accountId: Long): app.dot.mail.engine.SmimeDecryptionIdentity {
        val alias = config(accountId).decryptionKeyChainAlias ?: throw notConfigured()
        val (chain, key) = load(alias)
        return app.dot.mail.engine.SmimeDecryptionIdentity(chain.first(), key)
    }

    /** Reads the certificate behind [alias] for display; never throws. */
    suspend fun status(alias: String?): SmimeKeyStatus {
        if (alias.isNullOrBlank()) return SmimeKeyStatus.NotSet
        val leaf = try { withContext(Dispatchers.IO) { chainOf(context, alias)?.firstOrNull() } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ?: return SmimeKeyStatus.Unavailable
        return SmimeKeyStatus.Available(smimeSubjectName(leaf.subjectX500Principal), smimeSubjectName(leaf.issuerX500Principal), leaf.notBefore.time, leaf.notAfter.time, SmimeCertStore.certificateMailboxes(leaf))
    }

    private suspend fun load(alias: String): Pair<Array<X509Certificate>, PrivateKey> = withContext(Dispatchers.IO) {
        try {
            val chain = chainOf(context, alias)?.takeIf { it.isNotEmpty() }
            val key = keyOf(context, alias)
            if (chain == null || key == null) throw SmimeUnavailableException(SmimeUnavailableException.Reason.KEY_MISSING, "The S/MIME key \"$alias\" is no longer available")
            chain to key
        } catch (e: SmimeUnavailableException) { throw e }
        catch (e: CancellationException) { throw e }
        catch (e: SecurityException) { throw SmimeUnavailableException(SmimeUnavailableException.Reason.ACCESS_DENIED, "Access to the S/MIME key was denied", e) }
        catch (e: Exception) { throw SmimeUnavailableException(SmimeUnavailableException.Reason.KEY_MISSING, "The S/MIME key could not be read", e) }
    }

    private fun notConfigured() = SmimeUnavailableException(SmimeUnavailableException.Reason.NOT_CONFIGURED, "No S/MIME key is set for this account")
}
