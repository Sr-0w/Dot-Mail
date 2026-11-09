package app.dot.mail.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import app.dot.mail.data.canonicalContactEmail
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Bounded slices keep old large drafts/bodies out of CursorWindow during upgrade. */
internal fun backfillCorrespondence(db:SupportSQLiteDatabase) {
    fun addresses(value:Any?):List<Pair<String,String?>> = runCatching {
        val array=if(value is JSONArray) value else JSONArray(value as? String ?: "[]")
        buildList { for(i in 0 until array.length()) {
            val address=array.optJSONObject(i) ?: continue
            canonicalContactEmail(address.optString("email"))?.let { add(it to address.optString("name").takeIf { name -> name.isNotBlank() && name!="null" }?.take(512)) }
        } }
    }.getOrDefault(emptyList())
    fun known(account:Long,email:String,name:String?,at:Long) {
        db.execSQL("INSERT OR IGNORE INTO contacts(accountId,email,name,lastSeen,corresponded) VALUES(?,?,?,?,1)",arrayOf<Any?>(account,email,name,at))
        db.execSQL("UPDATE contacts SET corresponded=1 WHERE accountId=? AND email=?",arrayOf<Any?>(account,email))
    }
    var after=0L
    while(true) {
        var found=false
        db.query("SELECT m.id,m.accountId,m.header,m.sentAt,f.role FROM messages m JOIN folders f ON f.id=m.folderId WHERE m.id>? ORDER BY m.id LIMIT 100",arrayOf(after)).use { cursor ->
            while(cursor.moveToNext()) {
                found=true; val id=cursor.getLong(0); after=id; val account=cursor.getLong(1); val json=runCatching { JSONObject(cursor.getString(2)) }.getOrNull() ?: continue
                addresses(json.opt("from")).map { it.first }.distinct().take(256).forEach { db.execSQL("INSERT OR IGNORE INTO message_senders(messageId,email) VALUES(?,?)",arrayOf<Any?>(id,it)) }
                if(cursor.getString(4)=="SENT") for(field in listOf("to","cc","bcc")) addresses(json.opt(field)).forEach { known(account,it.first,it.second,cursor.getLong(3)) }
            }
        }
        if(!found) break
    }
    after=0
    while(true) {
        val batch=mutableListOf<Pair<Long,Long>>()
        db.query("SELECT id,accountId FROM outbox WHERE id>? AND state NOT IN('CANCELLED','DISCARDED') ORDER BY id LIMIT 100",arrayOf(after)).use { cursor -> while(cursor.moveToNext()) batch+=cursor.getLong(0) to cursor.getLong(1) }
        if(batch.isEmpty()) break
        for((id,account) in batch) for(field in listOf("to","cc","bcc")) {
            val prefix="\"$field\":"
            db.query("SELECT substr(draft,instr(draft,?)+?,65536) FROM outbox WHERE id=?",arrayOf<Any?>(prefix,prefix.length,id)).use { cursor ->
                if(cursor.moveToFirst()) {
                    val value=runCatching { JSONTokener(cursor.getString(0)).nextValue() }.getOrNull()
                    addresses(value).forEach { known(account,it.first,it.second,0) }
                }
            }
        }
        after=batch.last().first
    }
}
