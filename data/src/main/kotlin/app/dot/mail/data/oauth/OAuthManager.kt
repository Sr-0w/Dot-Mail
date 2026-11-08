package app.dot.mail.data.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import app.dot.mail.data.*
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.Credentials
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class OAuthManager(
    context: Context,
    private val database: MailDatabase,
    private val vault: CredentialVault,
    private val clients: Map<OAuthProvider, String>,
    private val endpoint: TokenEndpoint = HttpsTokenEndpoint(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val pending = context.getSharedPreferences("dotmail.oauth.pending", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val dao = database.mail()
    fun availability(provider: OAuthProvider) = OAuthAvailability(provider, !clients[provider].isNullOrBlank(), if (clients[provider].isNullOrBlank()) "OAuth sign-in is not available in this build" else null)
    private fun client(provider: OAuthProvider): OAuthClient {
        val id = clients[provider]?.takeIf { it.isNotBlank() } ?: throw OAuthException("OAuth sign-in is not available in this build", app.dot.mail.data.model.OAuthError.NOT_AVAILABLE)
        return OAuthClient(provider, id)
    }
    suspend fun begin(email: String, provider: OAuthProvider, accountId: Long?): Intent = withContext(Dispatchers.IO) { lock.withLock {
        val client = client(provider)
        if (!email.matches(Regex("[^\\s<>@]+@[^\\s<>@]+"))) throw OAuthException("Enter a valid email address", app.dot.mail.data.model.OAuthError.INVALID_EMAIL)
        if (accountId != null) {
            val existing = dao.account(accountId) ?: throw OAuthException("Account no longer exists", app.dot.mail.data.model.OAuthError.ACCOUNT_GONE)
            if (!existing.email.equals(email, true) || Codec.config(existing.config).imap.host != client.config(email).imap.host) throw OAuthException("Sign in with the same provider and address as this account", app.dot.mail.data.model.OAuthError.WRONG_ACCOUNT)
        }
        val session = OAuthSession(OAuthProtocol.random(), OAuthProtocol.random(), email, provider, client.id, client.redirect, now(), accountId)
        val encrypted = vault.encrypt("oauth-session:${session.state}", session.json())
        if (!pending.edit().clear().putString(session.state, encrypted).commit()) throw OAuthException("Could not save sign-in state", app.dot.mail.data.model.OAuthError.STORAGE)
        CustomTabsIntent.Builder().setShowTitle(true).build().intent.apply { data = OAuthProtocol.authorization(client, session) }
    } }
    suspend fun complete(uri: Uri, verify: suspend (AccountConfig, Credentials) -> Unit): Long = withContext(Dispatchers.IO) { lock.withLock {
        if (uri.authority != null || uri.path != "/oauth2redirect" || uri.fragment != null) throw OAuthException("Invalid authorization callback", app.dot.mail.data.model.OAuthError.INVALID_CALLBACK)
        val states = uri.getQueryParameters("state")
        if (states.size != 1 || states.single().length > 128) throw OAuthException("Invalid authorization state", app.dot.mail.data.model.OAuthError.INVALID_CALLBACK)
        val state = states.single()
        val encrypted = pending.getString(state, null) ?: throw OAuthException("No pending sign-in. Please start again.", app.dot.mail.data.model.OAuthError.NO_PENDING)
        val session = try { OAuthSession.parse(vault.decrypt("oauth-session:$state", encrypted)) } catch (_: Exception) { throw OAuthException("Saved sign-in state is unavailable. Please start again.", app.dot.mail.data.model.OAuthError.STATE_LOST) }
        val code = OAuthProtocol.validateCallback(uri, session, now())
        val client = client(session.provider)
        if (session.clientId != client.id || session.redirect != client.redirect) throw OAuthException("This build's sign-in configuration changed. Start again.", app.dot.mail.data.model.OAuthError.BUILD_CHANGED)
        if (!pending.edit().remove(state).commit()) throw OAuthException("Could not consume sign-in state", app.dot.mail.data.model.OAuthError.STORAGE)
        val response = try { endpoint.request(client.token, mapOf("grant_type" to "authorization_code", "client_id" to client.id, "redirect_uri" to client.redirect, "code" to code, "code_verifier" to session.verifier)) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { throw OAuthException("Could not complete sign-in. Please start again.", app.dot.mail.data.model.OAuthError.COMPLETE_FAILED) }
        val tokens = parseTokens(client, response, null)
        val config = client.config(session.email)
        try { verify(config, Credentials(tokens.access, oauth2 = true)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw OAuthException("Provider rejected IMAP or SMTP sign-in. Check the selected account, IMAP access, and authenticated SMTP policy.", app.dot.mail.data.model.OAuthError.PROVIDER_REJECTED) }
        val secret = vault.encrypt(session.email, tokens.json())
        if (session.accountId != null) {
            val existing = dao.account(session.accountId) ?: throw OAuthException("Account was removed during sign-in", app.dot.mail.data.model.OAuthError.ACCOUNT_GONE)
            if (existing.email != session.email || Codec.config(existing.config).imap.host != config.imap.host) throw OAuthException("Account changed during sign-in", app.dot.mail.data.model.OAuthError.ACCOUNT_CHANGED)
            dao.accountAuthorization(existing.id, secret, Codec.config(config.copy(displayName = existing.name))); existing.id
        } else dao.insertAccount(AccountRow(email = session.email, name = config.displayName, color = dao.accounts().size % 6, config = Codec.config(config), secret = secret))
    } }
    suspend fun credentials(accountId: Long): Credentials = withContext(Dispatchers.IO) { lock.withLock {
        val row = dao.account(accountId) ?: throw ReauthenticationRequired()
        val tokens = try { OAuthTokens.parse(vault.decrypt(row.email, row.secret)) } catch (_: Exception) { dao.syncError(row.id, "Sign in again to reconnect this account", true); throw ReauthenticationRequired() }
        val client = try { client(tokens.provider) } catch (_: OAuthException) { dao.syncError(row.id, "OAuth sign-in is not available in this build", true); throw ReauthenticationRequired() }
        if (tokens.clientId != client.id) { dao.syncError(row.id, "OAuth configuration changed; sign in again", true); throw ReauthenticationRequired() }
        if (tokens.expiresAt > now() + 60_000) return@withLock Credentials(tokens.access, oauth2 = true)
        val response = try { endpoint.request(client.token, mapOf("grant_type" to "refresh_token", "client_id" to client.id, "refresh_token" to tokens.refresh, "scope" to client.scopes.joinToString(" "))) }
        catch (e: TokenEndpointException) {
            if (e.reauthentication) { dao.syncError(row.id, "Authorization expired or was revoked. Sign in again.", true); throw ReauthenticationRequired() }
            throw e
        }
        val refreshed = parseTokens(client, response, tokens.refresh)
        // Never persist tokens after account deletion; UPDATE cannot recreate a removed account.
        if (dao.accountSecret(row.id, vault.encrypt(row.email, refreshed.json())) != 1) throw ReauthenticationRequired()
        Credentials(refreshed.access, oauth2 = true)
    } }
    private fun parseTokens(client: OAuthClient, json: JSONObject, previousRefresh: String?): OAuthTokens {
        if (!json.optString("token_type", "Bearer").equals("Bearer", true)) throw OAuthException("Unsupported provider token type", app.dot.mail.data.model.OAuthError.PROVIDER_RESPONSE)
        val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: throw OAuthException("Provider did not return an access token", app.dot.mail.data.model.OAuthError.PROVIDER_RESPONSE)
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: previousRefresh ?: throw OAuthException("Provider did not grant offline access. Start sign-in again.", app.dot.mail.data.model.OAuthError.PROVIDER_RESPONSE)
        val seconds = json.optLong("expires_in", 0)
        if (seconds !in 1..86_400) throw OAuthException("Provider returned an invalid token expiry", app.dot.mail.data.model.OAuthError.PROVIDER_RESPONSE)
        val granted = json.optString("scope").split(' ').filter { it.isNotBlank() }
        val required = client.scopes.filter { it != "offline_access" }
        if (granted.isNotEmpty() && required.any { requiredScope -> granted.none { it.equals(requiredScope, true) || (client.provider == OAuthProvider.MICROSOFT && it.equals(requiredScope.substringAfterLast('/'), true)) } }) throw OAuthException("Provider did not grant all required mail permissions", app.dot.mail.data.model.OAuthError.PROVIDER_RESPONSE)
        return OAuthTokens(client.provider, client.id, access, refresh, now() + seconds * 1000)
    }
}
