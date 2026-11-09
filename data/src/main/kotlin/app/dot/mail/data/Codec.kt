package app.dot.mail.data

import app.dot.mail.data.model.*
import app.dot.mail.engine.Header
import org.json.JSONArray
import org.json.JSONObject

internal object Codec {
    fun strings(values: List<String>) = JSONArray(values).toString()
    fun strings(raw: String): List<String> = JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } }
    fun addresses(values: List<EmailAddress>): String = JSONArray(values.map { JSONObject().put("email", it.email).put("name", it.name) }).toString()
    fun addresses(raw: String): List<EmailAddress> = JSONArray(raw).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { EmailAddress(it.getString("email"), it.nullString("name")) } } }
    private fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun server(s: ServerConfig) = JSONObject().put("host", s.host).put("port", s.port).put("security", s.security.name).put("user", s.username)
    private fun server(s: JSONObject) = ServerConfig(s.getString("host"), s.getInt("port"), Security.valueOf(s.getString("security")), s.getString("user"))
    private fun smime(s: SmimeAccountConfig) = JSONObject()
        .put("signingKeyChainAlias", s.signingKeyChainAlias)
        .put("decryptionKeyChainAlias", s.decryptionKeyChainAlias)
        .put("signByDefault", s.signByDefault)
        .put("encryptByDefault", s.encryptByDefault)
    private fun smime(json: JSONObject?): SmimeAccountConfig = json?.let {
        SmimeAccountConfig(
            signingKeyChainAlias = it.nullString("signingKeyChainAlias"),
            decryptionKeyChainAlias = it.nullString("decryptionKeyChainAlias"),
            signByDefault = it.optBoolean("signByDefault", false),
            encryptByDefault = it.optBoolean("encryptByDefault", false),
        )
    } ?: SmimeAccountConfig()
    fun config(c: AccountConfig): String = JSONObject()
        .put("email", c.email).put("name", c.displayName).put("imap", server(c.imap)).put("smtp", server(c.smtp))
        .put("auth", c.authType.name).put("hint", c.providerHint).put("smime", smime(c.smime)).toString()
    fun config(raw: String): AccountConfig = JSONObject(raw).let {
        AccountConfig(
            it.getString("email"), it.getString("name"), server(it.getJSONObject("imap")), server(it.getJSONObject("smtp")),
            AuthType.valueOf(it.getString("auth")), it.nullString("hint"), smime(it.optJSONObject("smime")),
        )
    }
    fun draft(d: Draft): String = JSONObject().put("id", d.localId).put("account", d.accountId).put("identity", d.identityId).put("to", addresses(d.to)).put("cc", addresses(d.cc)).put("bcc", addresses(d.bcc)).put("subject", d.subject).put("text", d.bodyText).put("html", d.bodyHtml).put("attachments", strings(d.attachmentUris)).put("reply", d.inReplyTo).put("refs", strings(d.references)).put("scheduled", d.scheduledAt).put("pgpSign", d.pgpSign).put("pgpEncrypt", d.pgpEncrypt).put("smimeSign", d.smimeSign).put("smimeEncrypt", d.smimeEncrypt).toString()
    fun draft(raw: String): Draft = JSONObject(raw).let { Draft(it.getLong("id"), it.getLong("account"), if (it.isNull("identity")) null else it.getLong("identity"), addresses(it.getString("to")), addresses(it.getString("cc")), addresses(it.getString("bcc")), it.getString("subject"), it.getString("text"), strings(it.getString("attachments")), it.nullString("reply"), strings(it.getString("refs")), if (it.isNull("scheduled")) null else it.getLong("scheduled"), bodyHtml = it.nullString("html"), pgpSign = it.optBoolean("pgpSign"), pgpEncrypt = it.optBoolean("pgpEncrypt"), smimeSign = it.optBoolean("smimeSign"), smimeEncrypt = it.optBoolean("smimeEncrypt")) }
    fun header(h: Header): String {
        fun convert(a: List<app.dot.mail.engine.Address>) = addresses(a.map { EmailAddress(it.email, it.name) })
        val signals=h.classificationHeaders.toMutableMap()
        h.unsubscribe?.let { unsubscribe -> if(signals.keys.none { it.equals("List-Unsubscribe",true) }) signals["List-Unsubscribe"]=listOf(unsubscribe) }
        val classifierInput=app.dot.mail.engine.text.ClassifierInput(h.from.firstOrNull()?.email.orEmpty(),h.from.firstOrNull()?.name.orEmpty(),h.subject,"",signals,h.hasPdfAttachment)
        val auto=app.dot.mail.engine.text.Classifier.classify(classifierInput).category
        val category=app.dot.mail.engine.text.Classifier.matchRules(classifierInput,ClassificationRules.current)?.category ?: auto
        return JSONObject().put("attachmentNames",strings(h.attachmentNames)).put("category",category.name).put("autoCategory",auto.name).put("listId",signals.entries.firstOrNull { it.key.equals("List-Id",true) }?.value?.firstOrNull()).put("from", convert(h.from)).put("to", convert(h.to)).put("cc", convert(h.cc)).put("bcc", convert(h.bcc)).put("replyTo", convert(h.replyTo)).put("threadingRevision", 1).put("inReplyTo", h.inReplyTo).put("gmailThreadId", h.gmailThreadId).put("messageId", h.messageId).put("refs", strings(h.references)).put("unsubscribe", h.unsubscribe).put("unsubOneClick", h.unsubscribeOneClick).put("encryptionHint", h.encryptionHint.name).toString()
    }
    /**
     * Re-applies the user's [rules] to a stored header: rule match (sender, domain, subject, List-Id) wins, otherwise the
     * heuristic category recorded at sync time. Returns the new header JSON, or null when nothing changes.
     */
    fun reclassified(raw: String, subject: String, rules: List<app.dot.mail.engine.text.Rule>): String? {
        val json = JSONObject(raw)
        val auto = json.nullString("autoCategory") ?: json.nullString("category") ?: return null
        val from = runCatching { addresses(json.getString("from")).firstOrNull() }.getOrNull()
        val headers = json.nullString("listId")?.let { mapOf("List-Id" to listOf(it)) }.orEmpty()
        val input = app.dot.mail.engine.text.ClassifierInput(from?.email.orEmpty(), from?.name.orEmpty(), subject, "", headers, false)
        val category = app.dot.mail.engine.text.Classifier.matchRules(input, rules)?.category?.name ?: auto
        if (json.nullString("category") == category && json.nullString("autoCategory") == auto) return null
        return json.put("autoCategory", auto).put("category", category).toString()
    }
    fun field(raw: String, name: String): String? = JSONObject(raw).nullString(name)
    fun encryptionHint(raw: String): app.dot.mail.engine.EncryptionHint = runCatching {
        app.dot.mail.engine.EncryptionHint.valueOf(JSONObject(raw).optString("encryptionHint", "NONE"))
    }.getOrDefault(app.dot.mail.engine.EncryptionHint.NONE)
    fun addressField(raw: String, name: String): List<EmailAddress> = addresses(JSONObject(raw).getString(name))
}
