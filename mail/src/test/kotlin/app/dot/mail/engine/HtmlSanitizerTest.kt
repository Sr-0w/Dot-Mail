package app.dot.mail.engine

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class HtmlSanitizerTest {
    @Test fun remoteImagesAreOnlyFetchedOverTls() {
        val html = HtmlSanitizer.sanitize(
            "<img src=\"http://img.example.org/logo.png\" width=\"200\"><table><tr><td background=\"http://img.example.org/bg.png\">x</td></tr></table>",
            allowRemoteImages = true,
        ).html
        assertTrue(html, "https://img.example.org/logo.png" in html)
        assertTrue(html, "https://img.example.org/bg.png" in html)
        assertFalse(html, "http://img.example.org" in html)
    }

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

    // ---- cell backgrounds and tracking pixels (AliExpress-style product grids) ------------------------------

    private val productCell = """<table width="184"><tr><td width="168" background="https://img.example.com/kf/product.png"
        style="background-size:cover;background-position:100% 100%;background-repeat:no-repeat;padding:148px 8px 0">
        <div>-12%</div></td></tr></table>"""

    @Test fun cellBackgroundIsBlockedLikeAnImageByDefault() {
        val res = HtmlSanitizer.sanitize(productCell)
        assertTrue(res.hasRemoteImages)
        assertEquals(1, res.blockedRemoteResources)
        assertFalse(res.html.contains("img.example.com"))
        assertFalse(res.html.contains("dotbg-"))
    }

    @Test fun cellBackgroundLoadsWhenImagesAreAllowed() {
        val res = HtmlSanitizer.sanitize(productCell, allowRemoteImages = true)
        val td = Jsoup.parse(res.html).selectFirst("td")!!
        assertTrue(td.attr("style"), td.attr("style").contains("background-image:url(\"https://img.example.com/kf/product.png\")"))
        assertTrue(td.attr("style").contains("background-size:cover"))
        assertFalse(td.hasAttr("background"))
        assertFalse(res.html.contains("dotbg-"))
        assertEquals(0, res.blockedRemoteResources)
    }

    @Test fun inlineBackgroundImageUrlTakesTheSameGate() {
        val html = "<td style=\"background-image:url('https://img.example.com/a.jpg');color:#fff\">x</td>"
        assertFalse(HtmlSanitizer.sanitize("<table><tr>$html</tr></table>").html.contains("img.example.com"))
        val allowed = Jsoup.parse(HtmlSanitizer.sanitize("<table><tr>$html</tr></table>", allowRemoteImages = true).html).selectFirst("td")!!.attr("style")
        assertTrue(allowed, allowed.contains("background-image:url(\"https://img.example.com/a.jpg\")"))
        assertTrue(allowed, allowed.contains("color:#fff"))
    }

    @Test fun backgroundUrlsMustBePlainHttp() {
        for (bad in listOf("javascript:alert(1)", "data:image/png;base64,AAAA", "https://x.example/a\")b", "https://x.example/a'b",
            "https://x.example/a b", "//x.example/a.png", "https://x.example/a\\b")) {
            assertNull("$bad -> ${HtmlSanitizer.backgroundUrl(bad, "")}", HtmlSanitizer.backgroundUrl(bad, ""))
            if ('"' in bad) continue // the HTML parser ends the attribute at the quote: a different (valid) value
            val out = HtmlSanitizer.sanitize("<table><tr><td background=\"$bad\">x</td></tr></table>", allowRemoteImages = true).html
            assertFalse(out, out.contains("background-image"))
        }
        // Escape attempts inside style url() never survive either.
        val out = HtmlSanitizer.sanitize("<div style=\"background:url(https://x.example/a.png) ;}</style><script>\">x</div>", allowRemoteImages = true).html
        assertFalse(out.contains("<script"))
    }

    @Test fun trackingPixelsNeverLoadEvenWhenImagesAreAllowed() {
        val html = """
            <img style="display:none" src="http://ae.mmstat.com/ae.edm.edm_open?tracelog=x">
            <img width="1" height="1" src="https://news.example.com/o.php?u=1">
            <img src="https://t.example.com/pixel.gif">
            <img src="https://example.us1.list-manage.com/track/open.php?u=1">
            <img width="1" height="20" src="https://cdn.example.com/spacer.gif">
            <img width="600" src="https://cdn.example.com/hero.jpg">"""
        val res = HtmlSanitizer.sanitize(html, allowRemoteImages = true)
        assertEquals(4, res.trackersBlocked)
        assertFalse(res.html.contains("mmstat"))
        assertFalse(res.html.contains("list-manage"))
        assertFalse(res.html.contains("pixel.gif"))
        assertFalse(res.html.contains("o.php"))
        assertTrue("spacer is layout, not a tracker", res.html.contains("spacer.gif"))
        assertTrue(res.html.contains("hero.jpg"))
    }

    @Test fun trackerBackgroundsAreDroppedToo() {
        val res = HtmlSanitizer.sanitize("<table><tr><td background=\"https://t.sendgrid.net/wf/open?upn=1\">x</td></tr></table>", allowRemoteImages = true)
        assertEquals(1, res.trackersBlocked)
        assertFalse(res.html.contains("sendgrid"))
    }

    // ---- responsive mails with leftover fixed widths (Infomaniak-style Foundation template) --------------------

    @Test fun responsiveMailsGetLeftoverFixedWidthsMadeFluid() {
        val html = """<style>@media only screen and (max-width: 670px){table.body .container{width:95% !important}}</style>
            <table class="body"><tr><td><center style="min-width:640px;width:100%">
            <table class="container" style="margin:0 auto;width:640px"><tr><td>Hi</td></tr></table>
            <table style="width: 640px;"><tr><td><img width="28" src="cid:x"> footer</td></tr></table>
            <table width="600"><tr><td style="width:20px">a</td></tr></table>
            </center></td></tr></table>"""
        val res = HtmlSanitizer.sanitize(html)
        assertTrue(res.responsive)
        assertEquals(640, res.layoutWidth)
        val doc = Jsoup.parse(res.html)
        assertFalse(res.html, Regex("(?<![a-z-])width:\\s*640px").containsMatchIn(res.html))
        assertTrue(doc.select("table").any { it.attr("style").contains("width:100%;max-width:640px") })
        assertTrue(doc.selectFirst("center")!!.attr("style").contains("min-width:0"))
        assertEquals("100%", doc.select("table[width]").first()!!.attr("width"))
        assertTrue(doc.select("table[width]").first()!!.attr("style").contains("max-width:600px"))
        assertTrue("small widths stay", res.html.contains("width:20px"))
    }

    @Test fun nonResponsiveFixedDesignsKeepTheirWidths() {
        val res = HtmlSanitizer.sanitize("<table style=\"width:640px\"><tr><td>x</td></tr></table>")
        assertFalse(res.responsive)
        assertTrue(res.html.contains("width:640px"))
    }

    @Test fun reportsLinksWhoseTextNamesAnotherSite() {
        val res = HtmlSanitizer.sanitize(
            "<a href='https://evil.example/login'>https://mybank.com/secure</a>" +
                "<a href='https://news.example/a'>Read more</a>" +
                "<a href='https://www.mybank.com/x'>mybank.com</a>",
        )
        assertEquals(mapOf("https://evil.example/login" to "https://mybank.com/secure"), res.suspiciousLinks)
    }

    @Test fun exposesTrackerCountThroughTheReaderWrapper() {
        val res = app.dot.mail.engine.html.HtmlSanitizer.sanitize("<p>Hi</p><img src='https://t.example/o.gif' width=1 height=1>")
        assertEquals(1, res.trackersBlocked)
        assertTrue(res.suspiciousLinks.isEmpty())
    }

    private val pixel = "<p>Hi</p><img src='https://t.example/o.gif' width='1' height='1'>"
    private val photo = "<p>Hi</p><img src='https://cdn.example/photo.jpg' width='600' height='400'>"
    private val trackedBackground = "<table><tr><td background='https://t.example/track/open'>x</td></tr></table>"

    @Test fun trackingPixelsAreRemovedEvenWhenImagesAreAllowedByDefault() {
        val res = HtmlSanitizer.sanitize(pixel, allowRemoteImages = true)
        assertEquals(1, res.trackersBlocked)
        assertFalse(res.html.contains("t.example"))
    }

    @Test fun withPixelBlockingOffAPixelFollowsTheRemoteImagePolicy() {
        val allowed = HtmlSanitizer.sanitize(pixel, allowRemoteImages = true, blockTrackers = false)
        assertEquals(0, allowed.trackersBlocked)
        assertTrue(allowed.html.contains("https://t.example/o.gif")) // loads only because remote images are allowed
        val blocked = HtmlSanitizer.sanitize(pixel, allowRemoteImages = false, blockTrackers = false)
        assertEquals(0, blocked.trackersBlocked)
        assertFalse(blocked.html.contains("t.example")) // still blocked by the separate remote-image policy
        assertEquals(1, blocked.blockedRemoteResources)
    }

    @Test fun trackedBackgroundsFollowTheSameToggle() {
        assertEquals(1, HtmlSanitizer.sanitize(trackedBackground, allowRemoteImages = true).trackersBlocked)
        val off = HtmlSanitizer.sanitize(trackedBackground, allowRemoteImages = true, blockTrackers = false)
        assertEquals(0, off.trackersBlocked)
        assertTrue(off.html.contains("background-image"))
    }

    @Test fun ordinaryImagesAreNeverTreatedAsTrackersWhateverTheToggle() {
        for (block in listOf(true, false)) {
            val res = HtmlSanitizer.sanitize(photo, allowRemoteImages = true, blockTrackers = block)
            assertEquals(0, res.trackersBlocked)
            assertTrue(res.html.contains("photo.jpg"))
        }
    }
}
