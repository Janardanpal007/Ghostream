package com.fastdownloader.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniboxTest {
    @Test
    fun addressesLoadAndWordsAreSearched() {
        assertEquals("https://example.com", Omnibox.resolve("example.com", "duckduckgo"))
        assertEquals("https://example.com/a?b=1", Omnibox.resolve("  example.com/a?b=1 ", "duckduckgo"))
        assertEquals("http://example.com", Omnibox.resolve("http://example.com", "duckduckgo"))
        // A phone keyboard's space after the dot.
        assertEquals("https://example.com", Omnibox.resolve("example. com", "duckduckgo"))
        assertEquals("http://localhost:8080", Omnibox.resolve("localhost:8080", "duckduckgo"))
        assertEquals("http://192.168.1.1/admin", Omnibox.resolve("192.168.1.1/admin", "duckduckgo"))
        assertEquals("https://duckduckgo.com/?q=how+to+cook+rice", Omnibox.resolve("how to cook rice", "duckduckgo"))
        assertEquals("https://search.brave.com/search?q=kotlin", Omnibox.resolve("kotlin", "brave"))
        assertTrue(Omnibox.resolve("magnet:?xt=urn:btih:abc", "google")!!.startsWith("magnet:"))
        assertNull(Omnibox.resolve("   ", "google"))
    }

    @Test
    fun unknownEngineFallsBackToFirst() {
        assertEquals(Omnibox.ENGINES[0], Omnibox.engine("nope"))
    }

    @Test
    fun searchTermsAreShownForResultPages() {
        assertEquals("cats & dogs", Omnibox.searchTerms(Omnibox.search("cats & dogs", "google")))
        assertNull(Omnibox.searchTerms("https://example.com/?q=x"))
    }

    @Test
    fun httpsUpgradeSkipsLocalAddresses() {
        assertEquals("https://example.com/page", Omnibox.httpsUpgradeOf("http://example.com/page"))
        assertNull(Omnibox.httpsUpgradeOf("https://example.com/"))
        assertNull(Omnibox.httpsUpgradeOf("http://localhost/"))
        assertNull(Omnibox.httpsUpgradeOf("http://192.168.0.10/"))
        assertNull(Omnibox.httpsUpgradeOf("http://example.com:8080/"))
        assertNull(Omnibox.httpsUpgradeOf("http://printer.local/"))
    }

    @Test
    fun hostsAndSites() {
        assertEquals("www.example.com", Omnibox.hostOf("https://user@www.Example.com:443/x?y#z"))
        assertEquals("example.com", Omnibox.displayHost("https://www.example.com/x"))
        assertTrue(Omnibox.matchesSite("m.example.com", "example.com"))
        assertFalse(Omnibox.matchesSite("notexample.com", "example.com"))
    }

    @Test
    fun desktopIdentityKeepsChromeVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.39 Mobile Safari/537.36"
        val desktop = Omnibox.desktopUserAgent(mobile)
        assertTrue(desktop.contains("Chrome/131.0.6778.39"))
        assertFalse(desktop.contains("Android"))
        assertFalse(desktop.contains("Mobile"))
    }

    @Test
    fun pagesTranslateIntoThePhonesLanguage() {
        assertEquals(
            "https://translate.google.com/translate?sl=auto&tl=hi&u=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1",
            Omnibox.translateUrl("https://example.com/a?b=1", java.util.Locale("hi", "IN")),
        )
        assertEquals("zh-TW", Omnibox.translateUrl("https://example.com/", java.util.Locale("zh", "TW"))!!.substringAfter("tl=").substringBefore("&"))
        assertNull(Omnibox.translateUrl("https://example-com.translate.goog/", java.util.Locale.ENGLISH))
        assertNull(Omnibox.translateUrl("about:blank", java.util.Locale.ENGLISH))
    }

    @Test
    fun desktopSwitchIsRemoved() {
        assertEquals("https://www.youtube.com/watch?v=x", Omnibox.withoutDesktopSwitch("https://www.youtube.com/watch?app=desktop&v=x"))
        assertEquals("https://www.youtube.com/watch#t", Omnibox.withoutDesktopSwitch("https://www.youtube.com/watch?app=desktop#t"))
        assertEquals("https://example.com/?a=1", Omnibox.withoutDesktopSwitch("https://example.com/?a=1"))
    }
}
