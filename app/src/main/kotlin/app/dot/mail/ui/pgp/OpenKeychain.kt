package app.dot.mail.ui.pgp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.resume

/**
 * OpenPGP through OpenKeychain's service API (the same integration as K-9 Mail / Thunderbird for Android). Dot Mail never
 * sees a private key or passphrase: OpenKeychain owns the keys and asks the user itself when needed. Plaintext comes back
 * in memory only and is never written to disk or indexed.
 */
object OpenKeychain {
    const val PACKAGE = "org.sufficientlysecure.keychain"
    const val FDROID_URL = "https://f-droid.org/packages/org.sufficientlysecure.keychain/"

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Signature verdict, reduced to what the reader shows. */
    enum class Verdict { VALID_CONFIRMED, VALID_UNCONFIRMED, INVALID, KEY_MISSING, KEY_EXPIRED_OR_REVOKED, UNSIGNED }

    data class Signature(val verdict: Verdict, val signer: String?)

    sealed interface Outcome {
        /** [output] is the decrypted (or verified) data; [signature] null when there was none. */
        data class Done(val output: ByteArray, val signature: Signature?) : Outcome
        /** OpenKeychain needs the user (passphrase, key selection, permission): launch [intent], then call again with its data. */
        data class NeedsUser(val intent: PendingIntent) : Outcome
        data class Failed(val message: String?) : Outcome
    }

    /**
     * Decrypts and/or verifies [input]. With [detachedSignature], [input] is the signed entity and only verification runs.
     * [resume] is the data Intent returned by a previous user-interaction round trip.
     */
    suspend fun decryptVerify(context: Context, input: () -> InputStream, detachedSignature: ByteArray? = null, resume: Intent? = null): Outcome =
        withService(context) { service ->
            val request = (resume ?: Intent()).apply {
                action = OpenPgpApi.ACTION_DECRYPT_VERIFY
                if (detachedSignature != null) putExtra(OpenPgpApi.EXTRA_DETACHED_SIGNATURE, detachedSignature)
            }
            val out = ByteArrayOutputStream()
            val result = input().use { OpenPgpApi(context, service).executeApi(request, it, out) }
            when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                OpenPgpApi.RESULT_CODE_SUCCESS -> Outcome.Done(out.toByteArray(), signature(result))
                OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED ->
                    result.parcelable<PendingIntent>(OpenPgpApi.RESULT_INTENT)?.let { Outcome.NeedsUser(it) } ?: Outcome.Failed(null)
                else -> Outcome.Failed(result.parcelable<OpenPgpError>(OpenPgpApi.RESULT_ERROR)?.message)
            }
        }

    private fun signature(result: Intent): Signature? {
        val s = result.parcelable<OpenPgpSignatureResult>(OpenPgpApi.RESULT_SIGNATURE) ?: return null
        val verdict = when (s.result) {
            OpenPgpSignatureResult.RESULT_NO_SIGNATURE -> return null
            OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED -> Verdict.VALID_CONFIRMED
            OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED -> Verdict.VALID_UNCONFIRMED
            OpenPgpSignatureResult.RESULT_KEY_MISSING -> Verdict.KEY_MISSING
            OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED, OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED -> Verdict.KEY_EXPIRED_OR_REVOKED
            else -> Verdict.INVALID
        }
        return Signature(verdict, s.primaryUserId)
    }

    // ---- composing: key lookup, detached signing, encryption --------------------------------------------------------

    /** One OpenKeychain call without the reader's decrypt specifics: either its result, or what it needs from the user. */
    sealed interface Step {
        class Done(val result: Intent, val output: ByteArray) : Step
        class NeedsUser(val intent: PendingIntent) : Step
        class Failed(val message: String?) : Step
    }

    /** [cancelled]: the user dismissed OpenKeychain's screen; otherwise [message] is its error, if any. */
    class PgpException(message: String?, val cancelled: Boolean = false) : Exception(message)

    private suspend fun call(context: Context, resume: Intent?, input: ByteArray?, build: Intent.() -> Unit): Step = withService(context) { service ->
        val request = (resume ?: Intent()).apply(build)
        val out = ByteArrayOutputStream()
        val result = OpenPgpApi(context, service).executeApi(request, input?.inputStream(), out)
        when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            OpenPgpApi.RESULT_CODE_SUCCESS -> Step.Done(result, out.toByteArray())
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED ->
                result.parcelable<PendingIntent>(OpenPgpApi.RESULT_INTENT)?.let { Step.NeedsUser(it) } ?: Step.Failed(null)
            else -> Step.Failed(result.parcelable<OpenPgpError>(OpenPgpApi.RESULT_ERROR)?.message)
        }
    }

    /** Runs [step] until done, handing OpenKeychain's own screens (passphrase, key choice) to [interact]. Null from it = cancelled. */
    private suspend fun interactive(interact: suspend (PendingIntent) -> Intent?, step: suspend (Intent?) -> Step): Step.Done {
        var resume: Intent? = null
        repeat(6) {
            when (val s = step(resume)) {
                is Step.Done -> return s
                is Step.Failed -> throw PgpException(s.message)
                is Step.NeedsUser -> resume = interact(s.intent) ?: throw PgpException(null, cancelled = true)
            }
        }
        throw PgpException(null)
    }

    /** Key ids OpenKeychain has for [email], or an empty array when there is none (or the choice is ambiguous). */
    suspend fun keyIds(context: Context, email: String): LongArray =
        when (val s = call(context, null, null) { action = OpenPgpApi.ACTION_GET_KEY_IDS; putExtra(OpenPgpApi.EXTRA_USER_IDS, arrayOf(email)) }) {
            is Step.Done -> s.result.getLongArrayExtra(OpenPgpApi.RESULT_KEY_IDS) ?: LongArray(0)
            else -> LongArray(0)
        }

    /** The key to sign with for [userId]; OpenKeychain may ask the user to choose once. */
    suspend fun signKeyId(context: Context, userId: String, interact: suspend (PendingIntent) -> Intent?): Long =
        interactive(interact) { resume -> call(context, resume, null) { action = OpenPgpApi.ACTION_GET_SIGN_KEY_ID; putExtra(OpenPgpApi.EXTRA_USER_ID, userId) } }
            .result.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0L).takeIf { it != 0L } ?: throw PgpException(null)

    /** Detached, ASCII-armoured signature over [data] and the hash algorithm used (as a PGP/MIME micalg). */
    suspend fun detachedSign(context: Context, data: ByteArray, signKeyId: Long, interact: suspend (PendingIntent) -> Intent?): Pair<ByteArray, String?> {
        val done = interactive(interact) { resume ->
            call(context, resume, data) { action = OpenPgpApi.ACTION_DETACHED_SIGN; putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true); putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, signKeyId) }
        }
        val signature = done.result.getByteArrayExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE) ?: throw PgpException(null)
        return signature to micalg(done.result.getStringExtra(OpenPgpApi.RESULT_SIGNATURE_MICALG))
    }

    /** ASCII-armoured ciphertext of [data] for [keyIds], also signed when [signKeyId] is given. */
    suspend fun encrypt(context: Context, data: ByteArray, keyIds: LongArray, signKeyId: Long?, interact: suspend (PendingIntent) -> Intent?): ByteArray =
        interactive(interact) { resume ->
            call(context, resume, data) {
                action = if (signKeyId != null) OpenPgpApi.ACTION_SIGN_AND_ENCRYPT else OpenPgpApi.ACTION_ENCRYPT
                putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true); putExtra(OpenPgpApi.EXTRA_KEY_IDS, keyIds)
                if (signKeyId != null) putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, signKeyId)
            }
        }.output

    /** OpenKeychain reports e.g. "SHA256"; PGP/MIME wants "pgp-sha256". */
    internal fun micalg(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("pgp-")) it else "pgp-$it" }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)

    private suspend fun <T> withService(context: Context, block: (IOpenPgpService2) -> T): T {
        val connection = suspendCancellableCoroutine<OpenPgpServiceConnection> { cont ->
            lateinit var conn: OpenPgpServiceConnection
            conn = OpenPgpServiceConnection(context.applicationContext, PACKAGE, object : OpenPgpServiceConnection.OnBound {
                override fun onBound(service: IOpenPgpService2) { if (cont.isActive) cont.resume(conn) }
                override fun onError(e: Exception) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
            })
            conn.bindToService()
            cont.invokeOnCancellation { runCatching { conn.unbindFromService() } }
        }
        return try { withContext(Dispatchers.IO) { block(connection.service) } } finally { runCatching { connection.unbindFromService() } }
    }
}
