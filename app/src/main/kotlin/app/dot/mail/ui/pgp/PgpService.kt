package app.dot.mail.ui.pgp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.dot.mail.data.PgpProtected
import app.dot.mail.data.PgpProtector

/** What the composer needs from OpenPGP; a fake in tests, OpenKeychain on the device. */
interface PgpService {
    /** OpenKeychain is installed, so Sign/Encrypt are offered. */
    fun available(): Boolean
    /** True when a public key for [email] is available for encryption. */
    suspend fun hasKey(email: String): Boolean
    /** [interact] launches OpenKeychain's own screen (passphrase, key choice) and returns its result, or null if cancelled. */
    fun protector(senderEmail: String, interact: suspend (PendingIntent) -> Intent?): PgpProtector
}

class OpenKeychainService(private val context: Context) : PgpService {
    override fun available() = OpenKeychain.installed(context)

    override suspend fun hasKey(email: String): Boolean = runCatching { OpenKeychain.keyIds(context, email).isNotEmpty() }.getOrDefault(false)

    override fun protector(senderEmail: String, interact: suspend (PendingIntent) -> Intent?) = PgpProtector { entity, sign, encrypt, recipients ->
        val signKey = if (sign) OpenKeychain.signKeyId(context, senderEmail, interact) else null
        if (encrypt) {
            val ids = recipients.flatMap { OpenKeychain.keyIds(context, it).toList() }.distinct().toLongArray()
            if (ids.isEmpty()) throw OpenKeychain.PgpException(null)
            PgpProtected(ciphertext = OpenKeychain.encrypt(context, entity, ids, signKey, interact))
        } else {
            val (signature, micalg) = OpenKeychain.detachedSign(context, entity, requireNotNull(signKey), interact)
            PgpProtected(signature = signature, micalg = micalg)
        }
    }
}
