package app.dot.mail.engine.text

import org.junit.Assert.*
import org.junit.Test

class SnippetsUnicodeTest {
    private fun validUtf16(value:String):Boolean {
        var index=0
        while(index<value.length) {
            val c=value[index++]
            if(Character.isHighSurrogate(c)) { if(index==value.length || !Character.isLowSurrogate(value[index++])) return false }
            else if(Character.isLowSurrogate(c)) return false
        }
        return true
    }
    @Test fun emojiCjkAndCombiningMarksSurvivePlainAndHtml() {
        val text="🔥 Smartphones 日本語 e\u0301"
        assertEquals(text,Snippets.fromPlainText(text))
        assertEquals(text,Snippets.fromHtml("<p>$text</p>"))
    }
    @Test fun truncationNeverSplitsSurrogatesOrCombiningClusters() {
        assertEquals("A…",Snippets.fromPlainText("A🔥B rest",3))
        assertEquals("A🔥…",Snippets.fromPlainText("A🔥B rest",4))
        assertEquals("日本語…",Snippets.fromPlainText("日本語中文",4))
        assertEquals("…",Snippets.fromPlainText("e\u0301 rest",2))
        assertEquals("e\u0301…",Snippets.fromPlainText("e\u0301 rest",3))
        for(limit in 1..15) {
            val snippet=Snippets.fromPlainText("🔥👍🏽👩‍💻 日本語 e\u0301 repeated",limit)
            assertTrue(snippet.length<=limit); assertTrue(validUtf16(snippet))
        }
    }
    @Test fun damagedPlainAlternativeUsesHealthyUnicodeHtmlWithoutInventingContent() {
        assertEquals("🔥 Smartphones",Snippets.fromBody("?? Smartphones","<p>🔥 Smartphones</p>"))
        assertEquals("日本語",Snippets.fromBody("??","<p>日本語</p>"))
        assertEquals("e\u0301",Snippets.fromBody("\uFFFD","<p>e\u0301</p>"))
        assertEquals("Normal plain",Snippets.fromBody("Normal plain","<p>🔥 Other HTML</p>"))
        assertEquals("Question??",Snippets.fromBody("Question??","<p>🔥 HTML</p>"))
        assertEquals("?? question",Snippets.fromBody("?? question","<p>ordinary HTML</p>"))
        assertEquals("?? question",Snippets.fromBody("?? question","<p>?? also damaged 🔥</p>"))
    }
}
