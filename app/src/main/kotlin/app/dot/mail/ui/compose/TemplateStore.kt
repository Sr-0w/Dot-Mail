package app.dot.mail.ui.compose

import android.content.Context
import androidx.core.content.edit
import app.dot.mail.engine.text.EmailTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Reusable email templates, stored on the device only (own prefs file; the app is excluded from backup). */
class TemplateStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("templates", Context.MODE_PRIVATE)
    private val _templates = MutableStateFlow(read())
    val templates: StateFlow<List<EmailTemplate>> = _templates.asStateFlow()

    private fun read(): List<EmailTemplate> = runCatching {
        val arr = JSONArray(prefs.getString("all", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            EmailTemplate(
                id = o.getLong("id"), name = o.getString("name"),
                subject = o.optString("subject").takeIf { o.has("subject") && !o.isNull("subject") },
                bodyText = o.getString("text"),
                bodyHtml = o.optString("html").takeIf { o.has("html") && !o.isNull("html") },
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<EmailTemplate>) {
        _templates.value = list
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("name", t.name).put("subject", t.subject ?: JSONObject.NULL)
                .put("text", t.bodyText).put("html", t.bodyHtml ?: JSONObject.NULL))
        }
        prefs.edit { putString("all", arr.toString()) }
    }

    @Synchronized fun save(name: String, subject: String?, bodyText: String, bodyHtml: String?) {
        val id = (_templates.value.maxOfOrNull { it.id } ?: 0) + 1
        write(_templates.value + EmailTemplate(id, name.trim().ifBlank { "Template $id" }, subject?.takeIf { it.isNotBlank() }, bodyText, bodyHtml?.takeIf { it.isNotBlank() }))
    }

    @Synchronized fun delete(id: Long) = write(_templates.value.filterNot { it.id == id })

    companion object {
        @Volatile private var instance: TemplateStore? = null
        fun get(context: Context): TemplateStore = instance ?: synchronized(this) {
            instance ?: TemplateStore(context.applicationContext).also { instance = it }
        }
    }
}
