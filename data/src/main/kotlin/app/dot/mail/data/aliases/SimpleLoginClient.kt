package app.dot.mail.data.aliases

import org.json.JSONObject

class SimpleLoginClient(private val baseUrl: String="https://app.simplelogin.io/api") : AliasProvider {
    override fun list(apiKey:String)=aliases(request("$baseUrl/v2/aliases?page_id=0",apiKey,"Authentication",{it}).let { it.optJSONObject("data") ?: it })
    override fun create(apiKey:String):EmailAlias { val j=request("$baseUrl/alias/random/new",apiKey,"Authentication",{it},"POST",JSONObject()); return alias(j) ?: error("Provider returned no alias") }
    override fun setEnabled(apiKey:String,alias:EmailAlias,enabled:Boolean):EmailAlias { val encoded=java.net.URLEncoder.encode(alias.id, Charsets.UTF_8.name()); request("$baseUrl/aliases/$encoded/toggle",apiKey,"Authentication",{it},"POST"); return alias.copy(active=enabled) }
}
