package app.dot.mail.data.aliases

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray

data class EmailAlias(val id: String, val email: String, val active: Boolean)
interface AliasProvider {
    fun list(apiKey: String): List<EmailAlias>
    fun create(apiKey: String): EmailAlias
    fun setEnabled(apiKey: String, id: String, enabled: Boolean): EmailAlias
}
internal fun request(url: String, key: String, auth: (String)->String, method: String="GET", body: JSONObject?=null): JSONObject {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod=method; connectTimeout=10000; readTimeout=10000
        setRequestProperty("Accept","application/json"); setRequestProperty("Authorization",auth(key))
        if(body!=null){ doOutput=true; setRequestProperty("Content-Type","application/json"); outputStream.use{it.write(body.toString().toByteArray())} }
    }
    try {
        val code=c.responseCode
        val stream=if(code in 200..299)c.inputStream else c.errorStream
        val text=stream?.bufferedReader()?.use{it.readText()} ?: "{}"
        check(code in 200..299) { "Alias provider request failed ($code)" }
        return JSONObject(text)
    } finally { c.disconnect() }
}
internal fun aliases(json: JSONObject): List<EmailAlias> {
    val array=json.optJSONArray("aliases") ?: json.optJSONObject("data")?.optJSONArray("data") ?: json.optJSONArray("data") ?: JSONArray()
    return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { a ->
        val email=a.optString("email", a.optString("alias")).takeIf(String::isNotBlank) ?: return@let null
        EmailAlias(a.optString("id", email),email,a.optBoolean("active",a.optBoolean("enabled",true)))
    } }
}
