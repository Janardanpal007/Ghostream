package com.fastdownloader.app.engine

import com.fastdownloader.app.engine.AdBlock.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdBlockTest {
    private val page = "https://news.example.com/article"

    private fun ab(vararg rules: String) = AdBlock.parse(listOf(rules.joinToString("\n")))

    @Test
    fun hostRulesBlockTheDomainAndItsSubdomains() {
        val a = ab("||ads.example^", "0.0.0.0 tracker.test")
        assertTrue(a.shouldBlock("https://ads.example/x.js", page, Type.SCRIPT))
        assertTrue(a.shouldBlock("https://cdn.ads.example/x.png", page, Type.IMAGE))
        assertTrue(a.shouldBlock("http://tracker.test/p", page, Type.XHR))
        assertFalse(a.shouldBlock("https://notads.example/x.js", page, Type.SCRIPT))
        assertFalse(a.shouldBlock("https://ads.example.org/x.js", page, Type.SCRIPT))
    }

    @Test
    fun patternsWildcardsAndSeparators() {
        val a = ab("/banner/*/ad_", "||cdn.example.com/ads/", "|https://x.test/ad.js|", "-advert-")
        assertTrue(a.shouldBlock("https://site.org/banner/123/ad_top.gif", page, Type.IMAGE))
        assertTrue(a.shouldBlock("https://cdn.example.com/ads/1.js", page, Type.SCRIPT))
        assertFalse(a.shouldBlock("https://cdn.example.com/video/ads.js", page, Type.SCRIPT))
        assertTrue(a.shouldBlock("https://x.test/ad.js", page, Type.SCRIPT))
        assertFalse(a.shouldBlock("https://x.test/ad.js?v=2", page, Type.SCRIPT))
        assertTrue(a.shouldBlock("https://foo.com/img-advert-1.png", page, Type.IMAGE))
    }

    @Test
    fun optionsLimitWhereRulesApply() {
        val a = ab(
            "||widgets.test^\$third-party",
            "||pix.test^\$image",
            "||only.test^\$domain=example.com|~shop.example.com",
            "||pop.test^\$popup",
        )
        assertTrue(a.shouldBlock("https://widgets.test/w.js", page, Type.SCRIPT))
        assertFalse(a.shouldBlock("https://widgets.test/w.js", "https://www.widgets.test/", Type.SCRIPT))
        assertTrue(a.shouldBlock("https://pix.test/a.gif", page, Type.IMAGE))
        assertFalse(a.shouldBlock("https://pix.test/a.js", page, Type.SCRIPT))
        assertTrue(a.shouldBlock("https://only.test/a", page, Type.SCRIPT))
        assertFalse(a.shouldBlock("https://only.test/a", "https://shop.example.com/", Type.SCRIPT))
        assertFalse(a.shouldBlock("https://only.test/a", "https://other.org/", Type.SCRIPT))
        assertFalse(a.shouldBlock("https://pop.test/", page, Type.SCRIPT))
        assertTrue(a.shouldBlock("https://pop.test/", page, Type.POPUP))
    }

    @Test
    fun exceptionsAndImportant() {
        val a = ab("||ads.example^", "@@||ads.example/allowed/", "||strong.test^\$important", "@@||strong.test^")
        assertTrue(a.shouldBlock("https://ads.example/x", page, Type.SCRIPT))
        assertFalse(a.shouldBlock("https://ads.example/allowed/x", page, Type.SCRIPT))
        assertTrue(a.shouldBlock("https://strong.test/x", page, Type.SCRIPT))
    }

    @Test
    fun badfilterSwitchesOffTheRuleItNamesAcrossLists() {
        val a = AdBlock.parse(
            listOf(
                "||ads.example^\n||cdn.test/ad.js\$script,domain=example.com\n||kept.test^",
                "||ads.example^\$badfilter\n||cdn.test/ad.js\$script,domain=example.com,badfilter",
            ),
        )
        assertFalse(a.shouldBlock("https://ads.example/x", page, Type.SCRIPT))
        assertFalse(a.shouldBlock("https://cdn.test/ad.js", page, Type.SCRIPT))
        assertTrue(a.shouldBlock("https://kept.test/x", page, Type.SCRIPT))
        assertEquals(1, a.networkRules)
    }

    @Test
    fun redirectRulesServeAStandInInsteadOfBlocking() {
        val a = ab(
            "||ads.test/ad.js\$script,redirect=noop.js",
            "||imasdk.googleapis.com/js/sdkloader/ima3.js\$script,redirect=google-ima.js:5",
            "||plain.test^",
            "||plain.test/gpt.js\$script,redirect-rule=googletagservices_gpt.js",
            "||only-rule.test/x.js\$redirect-rule=noop.js",
            "||safe.test^",
            "@@||safe.test^\$redirect=noop.js",
        )
        assertEquals("noop.js", a.blockOrRedirect("https://ads.test/ad.js", page, Type.SCRIPT))
        assertEquals("google-ima.js", a.blockOrRedirect("https://imasdk.googleapis.com/js/sdkloader/ima3.js", page, Type.SCRIPT))
        assertEquals("googletagservices_gpt.js", a.blockOrRedirect("https://plain.test/gpt.js", page, Type.SCRIPT))
        assertEquals("", a.blockOrRedirect("https://plain.test/other.js", page, Type.SCRIPT))
        // A $redirect-rule only changes how an already blocked request is answered; it doesn't block on its own.
        assertNull(a.blockOrRedirect("https://only-rule.test/x.js", page, Type.SCRIPT))
        // A redirect exception must not turn into a plain allow rule.
        assertTrue(a.shouldBlock("https://safe.test/x", page, Type.SCRIPT))
        assertNull(a.blockOrRedirect("https://example.org/app.js", page, Type.SCRIPT))
    }

    @Test
    fun redirectNamesAndAliasesFindAStandIn() {
        assertEquals("application/javascript", Redirects.find("noopjs")?.mime)
        assertEquals("google-ima.js", Redirects.find("google-ima.js")?.asset)
        assertEquals("anti-adblock.js", Redirects.find("nofab.js")?.asset)
        assertEquals("image/gif", Redirects.find("1x1.gif")?.mime)
        assertNull(Redirects.find("noop-1s.mp4")) // no stand-in: the request is simply blocked
        // Every asset a resource names must ship with the app.
        val names = listOf("google-ima.js", "googletagservices_gpt.js", "googlesyndication_adsbygoogle.js", "google-analytics.js", "anti-adblock.js")
        names.forEach { assertTrue(it, File("src/main/assets/redirects/$it").isFile) }
    }

    @Test
    fun proceduralRulesAreServedPerSiteAndNotAsCss() {
        val a = ab(
            "example.com##div.card:has-text(Sponsored)",
            "example.com#?#div:-abp-contains(Promoted)",
            "example.com##.x:upward(2):remove()",
            "example.com#@#.x:upward(2):remove()",
            "##div:has-text(ad)", // generic procedural rules are too slow to run everywhere: dropped
            "other.org##p.promo:style(color: red !important)",
            "other.org##p:style(background: url(https://x.test/a.png))", // can load things: dropped
            "other.org##div:others()", // not supported: dropped
        )
        assertEquals("""["div.card:has-text(Sponsored)","div:-abp-contains(Promoted)"]""", a.proceduralFor("https://www.example.com/"))
        assertEquals("", a.cosmeticBase("https://www.example.com/"))
        assertEquals("""["p.promo:style(color: red !important)"]""", a.proceduralFor("https://other.org/"))
        assertEquals("[]", a.proceduralFor("https://unrelated.net/"))
        assertEquals("[]", ab("@@||example.com^\$elemhide", "example.com##div:has-text(x)").proceduralFor("https://example.com/"))
    }

    @Test
    fun wholePageExceptions() {
        val a = ab("||ads.example^", "@@||trusted.org^\$document", "##.ad", "@@||plain.org^\$generichide", "plain.org##.local-ad")
        assertFalse(a.shouldBlock("https://ads.example/x", "https://trusted.org/page", Type.SCRIPT))
        assertEquals("", a.cosmeticFor("https://trusted.org/", ".ad"))
        assertEquals("", a.cosmeticFor("https://plain.org/", ".ad"))
        assertEquals(".local-ad", a.cosmeticBase("https://plain.org/"))
    }

    @Test
    fun elementHiding() {
        val a = ab(
            "##.ad-box", "###banner", "##div[id^=\"google_ads\"]", "example.com##.sponsored",
            "example.*##.entity", "~example.com##.not-here", "example.com#@#.ad-box", "##.x:has-text(Ad)", "example.com##+js(foo)",
        )
        assertEquals(".ad-box", a.cosmeticFor("https://foo.org/", ".ad-box .other"))
        assertEquals("#banner", a.cosmeticFor("https://foo.org/", "#banner"))
        assertEquals("", a.cosmeticFor("https://foo.org/", ".x")) // :has-text is skipped
        assertEquals("", a.cosmeticFor("https://news.example.com/", ".ad-box")) // #@# exception
        val base = a.cosmeticBase("https://news.example.com/").split('\n').toSet()
        assertTrue(".sponsored" in base)
        assertTrue(".entity" in base)
        assertTrue("div[id^=\"google_ads\"]" in base)
        assertFalse(".not-here" in base)
        assertTrue(".entity" in a.cosmeticBase("https://www.example.co.uk/"))
        assertTrue(".not-here" in a.cosmeticFor("https://other.org/", ".not-here"))
    }

    @Test
    fun scriptlets() {
        assertEquals(listOf("set", "a.b", "false"), AdBlock.scriptletArgs("set, a.b, false"))
        assertEquals(listOf("acs", "x", "y, z"), AdBlock.scriptletArgs("acs, x, 'y, z'"))
        assertEquals(listOf("nostif", "a,b", "500"), AdBlock.scriptletArgs("""nostif, a\,b, 500"""))
        assertEquals(listOf("rpfr", "/\"a\"/", ""), AdBlock.scriptletArgs("""rpfr, /"a"/, """))
        assertEquals(emptyList<String>(), AdBlock.scriptletArgs(""))

        val a = ab(
            "example.com,~shop.example.com##+js(set, ads.on, false)",
            "example.com##+js(nowoif)",
            "news.example.com#@#+js(nowoif)",
            "example.*##+js(aopr, adblock)",
            "other.org##+js(nowoif)",
            "other.org#@#+js()",
            "example.com##+js(not-a-scriptlet)",
            "##+js(nowoif)",
        )
        assertEquals(4, a.scriptletRules)
        val news = a.scriptletsFor("https://news.example.com/a")
        assertEquals("""[["set-constant","ads.on","false"],["abort-on-property-read","adblock"]]""", news)
        assertEquals(
            """[["set-constant","ads.on","false"],["no-window-open-if"],["abort-on-property-read","adblock"]]""",
            a.scriptletsFor("https://www.example.com/"),
        )
        assertEquals("""[["no-window-open-if"],["abort-on-property-read","adblock"]]""", a.scriptletsFor("https://shop.example.com/"))
        assertEquals("[]", a.scriptletsFor("https://other.org/")) // #@#+js() turns them all off
        assertEquals("[]", a.scriptletsFor("https://unrelated.net/")) // no generic scriptlets
        assertEquals("""[["set-constant","q","\"x\""]]""", ab("""a.com##+js(set, q, '"x"')""").scriptletsFor("https://a.com/"))
    }

    @Test
    fun helpers() {
        assertEquals("example.co.uk", AdBlock.baseDomain("www.news.example.co.uk"))
        assertEquals("example.com", AdBlock.baseDomain("a.b.example.com"))
        assertEquals("host.test", AdBlock.hostOf("https://user@host.test:8080/p?q"))
    }

    /** The real lists bundled with the app: they must load, block well-known ad hosts and leave normal pages alone. */
    @Test
    fun realFilterLists() {
        val dir = File("src/main/assets/filters")
        val lists = dir.listFiles()!!.filter { it.extension == "txt" }.map { it.readText() }
        val t0 = System.nanoTime()
        val a = AdBlock.parse(lists)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("Loaded ${a.networkRules} network, ${a.cosmeticRules} element-hiding and ${a.scriptletRules} scriptlet rules in $ms ms")
        assertTrue(a.networkRules > 50_000)
        assertTrue(a.cosmeticRules > 10_000)
        assertTrue(a.scriptletRules > 2_000)
        assertTrue("json-prune" in a.scriptletsFor("https://m.youtube.com/watch?v=x"))
        val yt = "https://www.youtube.com/watch?v=x"
        assertTrue(a.shouldBlock("https://securepubads.g.doubleclick.net/tag/js/gpt.js", page, Type.SCRIPT))
        assertTrue(a.shouldBlock("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", page, Type.SCRIPT))
        assertTrue(a.shouldBlock("https://www.google-analytics.com/analytics.js", page, Type.SCRIPT))
        assertFalse(a.shouldBlock("https://www.youtube.com/s/player/abc/base.js", yt, Type.SCRIPT))
        assertFalse(a.shouldBlock("https://rr1---sn-abc.googlevideo.com/videoplayback?id=1", yt, Type.MEDIA))
        assertFalse(a.shouldBlock("https://cdn.jsdelivr.net/npm/react@18.3.1/index.js", page, Type.SCRIPT))
        assertFalse(a.shouldBlock("https://upload.wikimedia.org/wikipedia/commons/a.png", "https://en.wikipedia.org/wiki/X", Type.IMAGE))

        // Speed: a typical page makes ~100 requests; each check should take well under a millisecond.
        val urls = List(2000) { "https://cdn$it.example.org/static/js/app.$it.min.js?v=$it" }
        val t1 = System.nanoTime()
        urls.forEach { a.shouldBlock(it, page, Type.SCRIPT) }
        val perCheckUs = (System.nanoTime() - t1) / 1000 / urls.size
        println("One check takes about $perCheckUs µs")
        assertTrue(perCheckUs < 1000)
    }
}
