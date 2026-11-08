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
        assertTrue(doc.select("script,iframe,object,embed,form,input,button,meta,link,svg,math,video,audio,source,base,template").isEmpty())
        doc.select("style").forEach { sheet ->
            val css = sheet.data()
            listOf("url(", "@import", "expression", "position", "\\", "@font-face", "behavior", "binding", "var(").forEach { bad ->
                assertFalse("Stylesheet kept $bad: $css", css.contains(bad, true))
            }
        }
        doc.body().allElements.forEach { el ->
            el.attributes().forEach { attr -> assertFalse(attr.key.startsWith("on", true)) }
            assertFalse(el.hasAttr("srcset")); assertFalse(el.hasAttr("background"))
            if (el.hasAttr("href")) assertTrue(el.attr("href").startsWith("https:") || el.attr("href").startsWith("http:") || el.attr("href").startsWith("mailto:"))
            assertFalse(el.hasAttr("src"))
        }
    }

    @Test fun stylesheetsKeepResponsiveRulesButNothingThatFetchesOrPositions() {
        val input = """
            <html><head><style>
            @import url(https://evil.invalid/a.css);
            @font-face { font-family: X; src: url(https://evil.invalid/f.woff) }
            /* comment */
            .container { width: 600px; background-color: #ffffff }
            .hero { background: url(https://evil.invalid/pixel.gif) }
            .overlay { position: fixed; top: 0; color: red }
            @media only screen and (max-width: 600px) { .container { width: 100% !important } .hide { display: none !important } }
            @keyframes spin { from { color: red } to { color: blue } }
            </style></head><body bgcolor="#f4f4f4"><table class="container" width="600"><tr><td bgcolor="#eeeeee">Hi</td></tr></table></body></html>
        """.trimIndent()
        val result = HtmlSanitizer.sanitize(input)
        assertSafe(result.html)
        val css = Jsoup.parse(result.html).select("style").joinToString("") { it.data() }
        assertTrue(css, css.contains(".container{width:600px;background-color:#ffffff}"))
        assertTrue(css, css.contains("@media only screen and (max-width: 600px){.container{width:100% !important}.hide{display:none !important}}"))
        assertTrue(css, css.contains(".overlay{color:red}"))
        assertFalse(css.contains("keyframes")); assertFalse(css.contains(".hero"))
        assertTrue(result.responsive)
        assertEquals(600, result.layoutWidth)
        assertTrue(result.html.contains("class=\"container\""))
        assertTrue(result.html.contains("bgcolor=\"#eeeeee\""))
        assertTrue(result.html.contains("background-color:#f4f4f4"))
    }
    @Test fun stylesheetWithEscapesOrMarkupIsDroppedEntirely() {
        assertEquals("", HtmlSanitizer.sanitizeStylesheet(".a{background:u\\72l(https://evil.invalid)}"))
        assertEquals("", HtmlSanitizer.sanitizeStylesheet(".a{color:red}</style><script>evil()</script>"))
        assertEquals("", HtmlSanitizer.sanitizeStylesheet(".a{color:red"))
        assertEquals(".a{color:rgb(1, 2, 3)}", HtmlSanitizer.sanitizeStylesheet(".a { color: rgb(1, 2, 3) }"))
        assertEquals("", HtmlSanitizer.sanitizeStylesheet("@media screen{@media print{.a{color:red}}}"))
    }
    @Test fun layoutWidthReflectsFixedDesignsOnly() {
        assertEquals(0, HtmlSanitizer.sanitize("<table width='100%'><tr><td>fluid</td></tr></table>").layoutWidth)
        assertEquals(640, HtmlSanitizer.sanitize("<div style='width:640px'>x</div>").layoutWidth)
        assertEquals(700, HtmlSanitizer.sanitize("<table><tr><td style='min-width: 700px !important'>x</td></tr></table>").layoutWidth)
        assertEquals(1280, HtmlSanitizer.sanitize("<img width='5000'>").layoutWidth)
        assertFalse(HtmlSanitizer.sanitize("<table width='600'></table>").responsive)
    }

    @Test fun blocksCrossRuleUnbalancedQuoteAndSelectorUrlInjection() {
        // P0: Unbalanced quote in declaration + selector containing `"` and `url(https:evil.invalid)`
        // must never emit `url(` or unbalanced quotes that swallow `}` in Chromium's CSS parser.
        val payload = """.a { background: " } " url(https:evil.invalid) { color: red }"""
        val sanitized = HtmlSanitizer.sanitizeStylesheet(payload)
        assertFalse("Cross-rule url() survived: $sanitized", sanitized.contains("url(", ignoreCase = true))
        assertEquals(0, sanitized.count { it == '"' } % 2)
        assertEquals(0, sanitized.count { it == '\'' } % 2)

        // Quotes are only valid when balanced inside font / font-family, never in color/background/width.
        assertEquals("", HtmlSanitizer.sanitizeStylesheet(""".a { color: "red" }"""))
        assertEquals("", HtmlSanitizer.sanitizeStylesheet(""".a { font-family: "Helvetica }"""))
        assertEquals(""".a{font-family:"Helvetica Neue", Arial, sans-serif}""", HtmlSanitizer.sanitizeStylesheet(""".a { font-family: "Helvetica Neue", Arial, sans-serif }"""))
    }

    @Test fun blocksCommentInStringDesyncAndUnclosedComments() {
        // P0: `/*` inside a string followed by `*/` in a later rule must not splice rules together.
        val desync = """
            .a { font-family: "/*"; }
            .b { background: url(https://evil.invalid); /* */ "; color: red; }
        """.trimIndent()
        val out = HtmlSanitizer.sanitizeStylesheet(desync)
        assertFalse("Comment-in-string desync spliced rules: $out", out.contains("font-family"))

        // Unclosed comment must not survive or corrupt subsequent rules.
        assertEquals(".a{color:red}", HtmlSanitizer.sanitizeStylesheet(".a { color: red } /* unclosed comment .b { color: blue }"))

        // Comment splitting `url/*...*/(` or `image-set(` or `@font-face` must still flag hasRemoteImages / blockedRemoteResources.
        val commentSplitUrl = HtmlSanitizer.sanitize("<div style='background: url/*c*/(https://evil.invalid/p)'>x</div>")
        assertTrue(commentSplitUrl.hasRemoteImages)
        assertTrue(commentSplitUrl.blockedRemoteResources > 0)

        val imageSetString = HtmlSanitizer.sanitize("<div style='background: image-set(\"https://evil.invalid/p.png\" 1x)'>x</div>")
        assertTrue(imageSetString.hasRemoteImages)
        assertTrue(imageSetString.blockedRemoteResources > 0)
    }

    @Test fun blocksAttributeSelectorsAndPseudoClassProbing() {
        // P1: Attribute selectors (`[href*=...]`) and relational pseudo-classes (`:has(...)`) can probe DOM secrets.
        assertEquals("", HtmlSanitizer.sanitizeStylesheet("""a[href*="secret"] { display: none }"""))
        assertEquals("", HtmlSanitizer.sanitizeStylesheet("""body:has(a[href^="https://"]) .probe { display: block }"""))
        assertEquals("", HtmlSanitizer.sanitizeStylesheet(""".a:nth-child(2n+1) { color: red }"""))
        assertEquals("", HtmlSanitizer.sanitizeStylesheet(""".a::after { color: red }"""))
        // Standard element, class, id, descendant, child, adjacent, and comma selectors still work.
        assertEquals("table.main > tbody > tr + tr, #header .title{color:red}", HtmlSanitizer.sanitizeStylesheet("table.main > tbody > tr + tr, #header .title { color: red }"))
    }

    @Test fun blocksUnbalancedMediaQueriesAndNegativeMarginSpoofing() {
        // P1: Unbalanced parens in @media prelude swallow subsequent blocks in Chromium.
        assertEquals("", HtmlSanitizer.sanitizeStylesheet("@media (max-width: 600px { .a { color: red } }"))
        assertEquals("", HtmlSanitizer.sanitizeStylesheet("@media ) (max-width: 600px) { .a { color: red } }"))
        assertEquals("", HtmlSanitizer.sanitizeStylesheet("@mediaall { .a { color: red } }"))

        // P1: Negative margins / text-indent can pull attacker content over earlier message text.
        assertEquals("", HtmlSanitizer.sanitizeStylesheet(".spoof { margin-top: -500px; margin: -20px 0; text-indent: -9999px; padding-left: -10px }"))
        // Normal non-negative margins and hyphenated keywords remain allowed.
        assertEquals(".ok{margin:0 auto;font-family:-apple-system, sans-serif}", HtmlSanitizer.sanitizeStylesheet(".ok { margin: 0 auto; font-family: -apple-system, sans-serif }"))
    }

    @Test fun capsHugeStylesheetsAgainstDos() {
        // P1: Huge stylesheets (>64KB or thousands of rules) must be bounded quickly without OOM or giant output.
        val tenThousandRules = buildString {
            repeat(5_000) { i -> append(".r$i { color: red; padding: 4px; }\n") }
        }
        val res = HtmlSanitizer.sanitize("<style>$tenThousandRules</style><p>Hello</p>")
        assertTrue("Stylesheet output was not capped: ${res.html.length}", res.html.length <= 70_000)
        assertTrue(res.html.contains("<p>Hello</p>"))
    }
}
