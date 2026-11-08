package app.dot.mail.engine

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class HtmlSanitizerTest {
    @Test fun rejectsExecutableMarkupAndSchemes() {
        val vectors = listOf(
            "<SCRIPT SRC=https://evil.invalid/a></SCRIPT>",
            "<svg><foreignObject><iframe srcdoc='<script>evil()</script>'></iframe></foreignObject></svg>",
            "<math><mtext><table><mglyph><style><!--</style><img title='--><img src=x onerror=evil()>'>",
            "<noscript><p title='</noscript><img src=x onerror=evil()>'>",
            "<template><script>evil()</script></template>",
            "<base href='https://evil.invalid/'><img src='/tracking'>",
            "<meta http-equiv=refresh content='0;url=https://evil.invalid'>",
            "<object data='javascript:evil()'></object><embed src='https://evil.invalid'>",
            "<form action='https://evil.invalid'><button formaction='javascript:evil()'>Go</button></form>",
            "<link rel=stylesheet href='https://evil.invalid'><style>@import url(https://evil.invalid);</style>",
        )
        vectors.forEach { input -> assertSafe(HtmlSanitizer.sanitize(input).html) }
        val badSchemes = listOf("javascript:evil()", "JaVaScRiPt:evil()", "java&#x73;cript:evil()", "java&#10;script:evil()", "vbscript:evil()", "data:text/html,evil", "file:///private/a", "content://untrusted/a", "intent://a", "//evil.invalid/a")
        badSchemes.forEach { scheme ->
            val html = HtmlSanitizer.sanitize("<a href='$scheme'>link</a><img src='$scheme'>").html
            assertSafe(html)
            assertFalse("Image source survived: $scheme", Jsoup.parse(html).select("img").any { it.hasAttr("src") })
        }
    }
    @Test fun stripsEventHandlersAcrossAllowedElements() {
        val events = listOf("onerror", "onload", "onclick", "onmouseover", "onfocus", "onanimationstart", "ontoggle", "onpointerenter", "onbeforeinput", "onwheel")
        listOf("img", "p", "a", "table", "td", "div", "span").forEach { tag ->
            events.forEach { event -> assertSafe(HtmlSanitizer.sanitize("<$tag $event='evil()' src='https://evil.invalid/a'>text</$tag>").html) }
        }
    }
    @Test fun blocksAllCssFetchMechanismsAndKeepsSimpleLayout() {
        val unsafe = listOf("background:url(https://evil.invalid/pixel)", "background-image:u\\72l(https://evil.invalid/pixel)", "list-style-image:url(//evil.invalid/pixel)", "cursor:url(https://evil.invalid/pixel)", "width:expression(evil())", "behavior:url(x.htc)", "-moz-binding:url(https://evil.invalid/a)", "background:image-set(url(https://evil.invalid/a) 1x)", "color:var(--evil)", "color:red/*evil*/", "position:fixed;inset:0;z-index:999999")
        unsafe.forEach { style ->
            val result = HtmlSanitizer.sanitize("<div style='$style' background='https://evil.invalid/a'><img srcset='https://evil.invalid/a 1x, https://evil.invalid/b 2x'></div>")
            assertSafe(result.html)
            assertTrue(result.hasRemoteImages)
            assertFalse(result.html.contains("url(", true)); assertFalse(result.html.contains("expression", true))
            assertFalse(result.html.contains("position")); assertFalse(result.html.contains("srcset"))
        }
        val safe = HtmlSanitizer.sanitize("<table style='width:100%;border-collapse:collapse'><tr><td style='padding:12px;color:#333;font-size:16px'>hello</td></tr></table>").html
        assertTrue(safe.contains("padding:12px")); assertTrue(safe.contains("border-collapse:collapse"))
    }
    @Test fun cidOnlyUsesExplicitTrustedMapAndCannotEnableNetwork() {
        val html = "<img src='cid:one'><img src='cid:missing'><img src='content://forged/a'>"
        val result = HtmlSanitizer.sanitize(html, cidUris = mapOf("one" to "content://dotmail/attachment/1"))
        val images = Jsoup.parse(result.html).select("img[src]")
        assertEquals(1, images.size); assertEquals("content://dotmail/attachment/1", images.single().attr("src"))
        assertFalse(HtmlSanitizer.sanitize("<img src='cid:one'>", cidUris = mapOf("one" to "https://evil.invalid/a")).html.contains("src="))
        val allowed = HtmlSanitizer.sanitize("<img src='https://example.org/image'><img src='file:///private/a'>", true)
        assertEquals(1, Jsoup.parse(allowed.html).select("img[src]").size)
    }
    private fun assertSafe(html: String) {
        val doc = Jsoup.parseBodyFragment(html)
        assertTrue(doc.select("script,iframe,object,embed,form,input,button,meta,link,style,svg,math,video,audio,source,base,template").isEmpty())
        doc.body().allElements.forEach { el ->
            el.attributes().forEach { attr -> assertFalse(attr.key.startsWith("on", true)) }
            assertFalse(el.hasAttr("srcset")); assertFalse(el.hasAttr("background"))
            if (el.hasAttr("href")) assertTrue(el.attr("href").startsWith("https:") || el.attr("href").startsWith("http:") || el.attr("href").startsWith("mailto:"))
            assertFalse(el.hasAttr("src"))
        }
    }
}
