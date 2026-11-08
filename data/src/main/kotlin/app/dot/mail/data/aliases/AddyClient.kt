package app.dot.mail.data.aliases

import org.json.JSONObject

class AddyClient(private val baseUrl:String="https://app.addy.io/api/v1") : AliasProvider {
    override fun list(apiKey:String)=aliases(request("$baseUrl/aliases",apiKey,{"Bearer $it"}))
    override fun create(apiKey:String):EmailAlias { val j=request("$baseUrl/aliases",apiKey,{"Bearer $it"},"POST",JSONObject().put("domain","anonaddy.me").put("format","random")); return (aliases(JSONObject().put("data", listOf(j.optJSONObject("data") ?: j))).firstOrNull() ?: EmailAlias(j.optString("id"),j.optString("email"),true)) }
    override fun setEnabled(apiKey:String,id:String,enabled:Boolean):EmailAlias { val action=if(enabled)"activate" else "deactivate"; val j=request("$baseUrl/aliases/$action/$id",apiKey,{"Bearer $it"},"POST"); return aliases(JSONObject().put("data",listOf(j.optJSONObject("data")?:j))).firstOrNull() ?: EmailAlias(id,j.optString("email"),enabled) }
}
