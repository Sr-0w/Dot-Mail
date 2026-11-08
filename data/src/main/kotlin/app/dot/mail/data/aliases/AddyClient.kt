package app.dot.mail.data.aliases

import org.json.JSONObject

class AddyClient(private val baseUrl: String = "https://app.addy.io/api/v1") : AliasProvider {
    override fun list(apiKey: String) = aliases(request("$baseUrl/aliases", apiKey, "Authorization", { "Bearer $it" }))

    override fun create(apiKey: String): EmailAlias {
        val options = request("$baseUrl/domain-options", apiKey, "Authorization", { "Bearer $it" })
        val payload = options.optJSONObject("data") ?: options
        val domain = payload.optString("defaultAliasDomain").takeIf(String::isNotBlank)
            ?: error("No default alias domain is configured")
        val result = request("$baseUrl/aliases", apiKey, "Authorization", { "Bearer $it" }, "POST",
            JSONObject().put("domain", domain).put("format", "random_characters"))
        return alias(result) ?: error("Provider returned no alias")
    }

    override fun setEnabled(apiKey: String, id: String, enabled: Boolean): EmailAlias {
        val encoded = java.net.URLEncoder.encode(id, Charsets.UTF_8.name())
        val result = if (enabled) request("$baseUrl/active-aliases", apiKey, "Authorization", { "Bearer $it" }, "POST", JSONObject().put("id", id))
        else request("$baseUrl/active-aliases/$encoded", apiKey, "Authorization", { "Bearer $it" }, "DELETE")
        return alias(result) ?: EmailAlias(id, "", enabled)
    }
}
