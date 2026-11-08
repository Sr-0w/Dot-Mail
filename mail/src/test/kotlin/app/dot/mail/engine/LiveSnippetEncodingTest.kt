package app.dot.mail.engine

import app.dot.mail.engine.text.Snippets
import kotlinx.coroutines.currentCoroutineContext
import org.eclipse.angus.mail.imap.IMAPFolder
import jakarta.mail.FetchProfile
import jakarta.mail.UIDFolder
import jakarta.mail.Folder
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Only aggregate encoding diagnostics leave the test process, never subjects, addresses, body text or secrets. */
class LiveSnippetEncodingTest {
    @Test fun compareAffectedPreviewWithMimeAlternatives() = runBlocking<Unit> {
        val file=Path.of(System.getProperty("user.home"),".config","dotmail","test-account.env")
        assumeTrue(System.getenv("DOTMAIL_LIVE")=="1" && Files.isRegularFile(file))
        val env=Files.readAllLines(file).filter { '=' in it && !it.trimStart().startsWith('#') }.associate { it.substringBefore('=').trim().removePrefix("export ") to it.substringAfter('=').trim().trim('"','\'') }
        val email=requireNotNull(env["DOTMAIL_TEST_EMAIL"]); val password=requireNotNull(env["DOTMAIL_TEST_PASSWORD"])
        val client=MailClient(); val endpoint=Endpoint("mail.infomaniak.com",993,email); val creds=Credentials(password)
        fun supplementary(text:String)=text.codePoints().filter { it>0xFFFF }.count()
        var found=false
        withTimeout(120_000) { client.withImapSession(endpoint,creds) {
            for(folder in client.folders(endpoint,creds).sortedBy { if(it.role==Role.SPAM) 0 else 1 }) {
                val result=client.sync(endpoint,creds,folder.path,limit=200,windowDays=30)
                val ids=result.headers.takeLast(200).map { it.uid }.toLongArray()
                if(ids.isEmpty()) continue
                val opened=currentCoroutineContext()[ImapScope]!!.store.getFolder(folder.path) as IMAPFolder
                opened.open(Folder.READ_ONLY)
                val previews=try {
                    val messages=opened.getMessagesByUID(ids).filterNotNull().toTypedArray()
                    opened.fetch(messages,FetchProfile().apply { add(FetchProfile.Item.CONTENT_INFO); add(UIDFolder.FetchProfileItem.UID) })
                    messages.associate { message -> val uid=opened.getUID(message); uid to SnippetFetcher.fetch(opened,message,uid,2048,fallbackToHtml=false) }
                } finally { opened.close(false) }
                val affected=previews.entries.firstOrNull { it.value.trimStart().startsWith("??") || it.value.contains('\uFFFD') } ?: continue
                val repaired=client.snippets(endpoint,creds,folder.path,longArrayOf(affected.key),result.cursor.uidValidity).values.single()
                val body=client.body(endpoint,creds,folder.path,affected.key,result.cursor.uidValidity)
                val plain=Snippets.fromPlainText(body.text.orEmpty())
                val html=Snippets.fromHtml(body.html.orEmpty())
                println("ENCODING preview_prefix_question_pair=${affected.value.trimStart().startsWith("??")} plain_prefix_question_pair=${plain.trimStart().startsWith("??")} html_prefix_question_pair=${html.trimStart().startsWith("??")}")
                println("ENCODING preview_supplementary=${supplementary(affected.value)} plain_supplementary=${supplementary(plain)} html_supplementary=${supplementary(html)} preview_replacement=${affected.value.count { it=='\uFFFD' }} plain_replacement=${plain.count { it=='\uFFFD' }} html_replacement=${html.count { it=='\uFFFD' }}")
                println("ENCODING repaired_prefix_question_pair=${repaired.trimStart().startsWith("??")} repaired_supplementary=${supplementary(repaired)}")
                for(bound in listOf(2048,8192,16384,32768)) {
                    val prefix=Snippets.fromHtml(body.html.orEmpty().take(bound))
                    println("ENCODING html_decoded_prefix=$bound empty=${prefix.isEmpty()} supplementary=${supplementary(prefix)}")
                }
                if(plain.trimStart().startsWith("??") && supplementary(html)>0) {
                    assertFalse("Bounded HTML fallback did not repair the affected preview",repaired.trimStart().startsWith("??"))
                    assertTrue("Repaired preview lost its supplementary character",supplementary(repaired)>0)
                }
                found=true; break
            }
        } }
        println("ENCODING affected_fixture_found=$found")
    }
}
