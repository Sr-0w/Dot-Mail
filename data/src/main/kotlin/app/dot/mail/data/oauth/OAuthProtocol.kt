package app.dot.mail.data.oauth

import app.dot.mail.data.model.*
import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

internal data class OAuthClient(val provider: OAuthProvider, val id: String) {
    val redirect = if (provider == OAuthProvider.GOOGLE) "com.googleusercontent.apps.${id.removeSuffix(".apps.googleusercontent.com")}:/oauth2redirect" else "app.dot.mail:/oauth2redirect"
    val authorization = when (provider) { OAuthProvider.GOOGLE -> "https://accounts.google.com/o/oauth2/v2/auth"; OAuthProvider.MICROSOFT -> "https://login.microsoftonline.com/common/oauth2/v2.0/authorize" }
    val token = when (provider) { OAuthProvider.GOOGLE -> "https://oauth2.googleapis.com/token"; OAuthProvider.MICROSOFT -> "https://login.microsoftonline.com/common/oauth2/v2.0/token" }
    val scopes = when (provider) { OAuthProvider.GOOGLE -> listOf("https://mail.google.com/"); OAuthProvider.MICROSOFT -> listOf("https://outlook.office.com/IMAP.AccessAsUser.All", "https://outlook.office.com/SMTP.Send", "offline_access") }
    fun config(email: String) = AccountConfig(email, email.substringBefore('@'),
        ServerConfig(if (provider == OAuthProvider.GOOGLE) "imap.gmail.com" else "outlook.office365.com", 993, Security.SSL_TLS, email),
        ServerConfig(if (provider == OAuthProvider.GOOGLE) "smtp.gmail.com" else if (email.substringAfter('@').matches(Regex("(?i)(outlook|hotmail|live|msn|windowslive|livemail)[.].+"))) "smtp-mail.outlook.com" else "smtp.office365.com", 587, Security.STARTTLS, email), AuthType.OAUTH2)
}
internal data class OAuthSession(val state: String, val verifier: String, val email: String, val provider: OAuthProvider, val clientId: String, val redirect: String, val createdAt: Long, val accountId: Long?) {
    fun json() = JSONObject().put("state", state).put("verifier", verifier).put("email", email).put("provider", provider.name).put("clientId", clientId).put("redirect", redirect).put("at", createdAt).put("account", accountId).toString()
    companion object {
        fun parse(raw: String) = JSONObject(raw).let { OAuthSession(it.getString("state"), it.getString("verifier"), it.getString("email"), OAuthProvider.valueOf(it.getString("provider")), it.getString("clientId"), it.getString("redirect"), it.getLong("at"), if(it.isNull("account")) null else it.getLong("account")) }
    }
}
internal data class OAuthTokens(val provider: OAuthProvider, val clientId: String, val access: String, val refresh: String, val expiresAt: Long) {
    fun json() = JSONObject().put("kind", "oauth2").put("provider", provider.name).put("clientId", clientId).put("access", access).put("refresh", refresh).put("expires", expiresAt).toString()
    companion object {
        fun parse(raw: String) = JSONObject(raw).let { require(it.getString("kind") == "oauth2"); OAuthTokens(OAuthProvider.valueOf(it.getString("provider")), it.getString("clientId"), it.getString("access"), it.getString("refresh"), it.getLong("expires")) }
    }
}
internal object OAuthProtocol {
    fun random() = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun challenge(verifier: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun authorization(client: OAuthClient, session: OAuthSession): Uri = Uri.parse(client.authorization).buildUpon()
        .appendQueryParameter("client_id", client.id).appendQueryParameter("redirect_uri", client.redirect)
        .appendQueryParameter("response_type", "code").appendQueryParameter("scope", client.scopes.joinToString(" "))
        .appendQueryParameter("state", session.state).appendQueryParameter("code_challenge", challenge(session.verifier))
        .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("login_hint", session.email)
        .appendQueryParameter("prompt", if (client.provider == OAuthProvider.GOOGLE) "consent" else "select_account")
        .apply { if (client.provider == OAuthProvider.GOOGLE) appendQueryParameter("access_type", "offline") }.build()
    fun validateCallback(uri: Uri, session: OAuthSession, now: Long): String {
        if (uri.scheme != Uri.parse(session.redirect).scheme || uri.authority != null || uri.path != "/oauth2redirect" || uri.fragment != null) throw OAuthException("Invalid authorization callback", app.dot.mail.data.model.OAuthError.INVALID_CALLBACK)
        val states = uri.getQueryParameters("state")
        if (states.size != 1 || !MessageDigest.isEqual(states.single().toByteArray(), session.state.toByteArray())) throw OAuthException("Authorization state did not match", app.dot.mail.data.model.OAuthError.INVALID_CALLBACK)
        if (now - session.createdAt !in 0..600_000) throw OAuthException("Sign-in expired. Please start again.", app.dot.mail.data.model.OAuthError.EXPIRED)
        if (uri.getQueryParameters("error").isNotEmpty()) throw OAuthException("Sign-in was cancelled or denied by the provider", app.dot.mail.data.model.OAuthError.DENIED)
        val codes = uri.getQueryParameters("code")
        if (codes.size != 1 || codes.single().isBlank() || codes.single().length > 8192) throw OAuthException("Authorization code is missing or invalid", app.dot.mail.data.model.OAuthError.INVALID_CALLBACK)
        return codes.single()
    }
}
internal class ReauthenticationRequired(cause: Throwable? = null) : Exception("Sign in again to reconnect this account", cause)
