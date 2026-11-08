package app.dot.mail.data.oauth

import app.dot.mail.data.model.OAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

internal class TokenEndpointException(val reauthentication: Boolean) : Exception("OAuth token request failed")
internal fun interface TokenEndpoint { suspend fun request(url: String, fields: Map<String, String>): JSONObject }
internal class HttpsTokenEndpoint : TokenEndpoint {
    override suspend fun request(url: String, fields: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        require(url in setOf("https://oauth2.googleapis.com/token", "https://login.microsoftonline.com/common/oauth2/v2.0/token"))
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(fields.entries.joinToString("&") { (key,value) -> "${URLEncoder.encode(key,"UTF-8")}=${URLEncoder.encode(value,"UTF-8")}" }.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val count = input.read(buffer); if (count < 0) break; if (output.size() + count > 65_536) throw OAuthException("Provider response was too large", app.dot.mail.data.model.OAuthError.PROVIDER_RESPONSE); output.write(buffer,0,count) }
                output.toString("UTF-8")
            }.orEmpty()
            val result = runCatching { JSONObject(raw) }.getOrElse { throw TokenEndpointException(false) }
            if (status !in 200..299 || result.has("error")) throw TokenEndpointException(result.optString("error") in setOf("invalid_grant", "interaction_required", "consent_required", "invalid_client", "unauthorized_client"))
            result
        } finally { connection.disconnect() }
    }
}
