package app.dot.mail.data.aliases

import org.json.JSONObject

class SimpleLoginClient(private val baseUrl: String="https://app.simplelogin.io/api") : AliasProvider {
    override fun list(apiKey:String)=aliases(request("$baseUrl/v2/aliases?page_id=0",apiKey,{it}).let { it.optJSONObject("data") ?: it })
    override fun create(apiKey:String):EmailAlias { val j=request("$baseUrl/alias/random/new",apiKey,{it},"POST",JSONObject()); return (aliases(JSONObject().put("aliases", listOf(j))).firstOrNull() ?: EmailAlias(j.optString("id"),j.optString("email"),true)) }
    override fun setEnabled(apiKey:String,id:String,enabled:Boolean):EmailAlias { val j=request("$baseUrl/aliases/$id/toggle",apiKey,{it},"POST"); return EmailAlias(id,j.optString("email"),enabled) }
}
