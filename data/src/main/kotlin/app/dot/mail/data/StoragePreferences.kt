package app.dot.mail.data

import android.content.Context
import app.dot.mail.data.model.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/** Settings/progress contain no credentials or message content. commit() is called from repository IO. */
internal class StoragePreferences(context:Context) {
    private val prefs=context.getSharedPreferences("dotmail.storage",Context.MODE_PRIVATE)
    val version=MutableStateFlow(0L)
    fun globalLimit():Long=prefs.getLong("global.limit",0)
    fun globalLimit(bytes:Long) { require(bytes==0L || bytes>=64L*1024*1024); check(prefs.edit().putLong("global.limit",bytes).commit()); version.value++ }
    fun configured(id:Long)=prefs.contains("$id.policy")
    fun policy(id:Long):StoragePolicy = runCatching {
        val json=JSONObject(prefs.getString("$id.policy","{}")!!); val defaults=StoragePolicy()
        StoragePolicy(RetentionMode.valueOf(json.optString("retention",defaults.retention.name)),json.optInt("latest",defaults.latestMessages),json.optInt("days",defaults.days),json.optLong("maxBytes",defaults.maxBytes),json.optBoolean("bodies",defaults.downloadBodies),json.optBoolean("attachments",defaults.downloadAttachments),json.optBoolean("wifi",defaults.wifiOnly),json.optBoolean("charging",defaults.chargingOnly))
    }.getOrDefault(StoragePolicy())
    fun save(id:Long,policy:StoragePolicy) {
        pruneCursor(id,0)
        require(policy.latestMessages in 1..1_000_000 && policy.days in 1..36500 && policy.maxBytes>=0) { "Invalid storage policy" }
        val p=policy.copy(downloadBodies=policy.downloadBodies || policy.downloadAttachments)
        val json=JSONObject().put("retention",p.retention.name).put("latest",p.latestMessages).put("days",p.days).put("maxBytes",p.maxBytes).put("bodies",p.downloadBodies).put("attachments",p.downloadAttachments).put("wifi",p.wifiOnly).put("charging",p.chargingOnly)
        check(prefs.edit().putString("$id.policy",json.toString()).commit()) { "Could not save storage policy" }; version.value++
    }
    fun pruneCursor(id:Long)=prefs.getInt("$id.prune",0)
    fun pruneCursor(id:Long,value:Int) { prefs.edit().putInt("$id.prune",value).commit() }
    fun paused(id:Long)=prefs.getBoolean("$id.paused",false)
    fun pause(id:Long,value:Boolean) { check(prefs.edit().putBoolean("$id.paused",value).commit()); version.value++ }
    fun progress(id:Long):OfflineProgress = runCatching {
        val j=JSONObject(prefs.getString("$id.progress","{}")!!)
        OfflineProgress(id,OfflineState.valueOf(j.optString("state","IDLE")),j.optInt("cached"),j.optInt("total"),j.optInt("bodies"),j.optString("error").ifBlank { null },j.optInt("skipped"))
    }.getOrDefault(OfflineProgress(id))
    fun progress(value:OfflineProgress) {
        val json=JSONObject().put("state",value.state.name).put("cached",value.cachedMessages).put("total",value.totalMessages).put("bodies",value.downloadedBodies).put("error",value.error.orEmpty()).put("skipped",value.skippedMessages)
        check(prefs.edit().putString("${value.accountId}.progress",json.toString()).commit()); version.value++
    }
    fun forget(id:Long) { val edit=prefs.edit(); listOf("policy","paused","progress","prune").forEach { edit.remove("$id.$it") }; edit.commit(); version.value++ }
}
