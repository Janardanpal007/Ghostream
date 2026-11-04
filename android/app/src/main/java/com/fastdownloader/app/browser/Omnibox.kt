package com.fastdownloader.app.browser

import java.net.URLEncoder

/** A search engine the address bar can use. None of them need an account or a key. */
data class SearchEngine(val id: String, val name: String, val query: String, val home: String)

/** What the address bar does with typed text. Plain Kotlin (no Android classes) so it's unit tested. */
object Omnibox {
    val ENGINES = listOf(
        SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/"),
        SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s", "https://search.brave.com/"),
        SearchEngine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s", "https://www.startpage.com/"),
        SearchEngine("google", "Google", "https://www.google.com/search?q=%s", "https://www.google.com/"),
        SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com/"),
        SearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s", "https://www.ecosia.org/"),
        SearchEngine("qwant", "Qwant", "https://www.qwant.com/?q=%s", "https://www.qwant.com/"),
    )

    fun engine(id: String) = ENGINES.firstOrNull { it.id == id } ?: ENGINES[0]

    private val HOST_LIKE = Regex("""^(localhost|\[[0-9a-fA-F:]+]|(\d{1,3}\.){3}\d{1,3}|([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,63})(:\d{1,5})?([/?#].*)?$""")

    /**
     * Typed text → the URL to load. Links load as they are, things that look like an address get https://,
     * everything else is searched with [engineId]. Returns null for blank text.
     */
    fun resolve(text: String, engineId: String): String? {
        val typed = text.trim()
        if (typed.isEmpty()) return null
        // "example. com" (a phone keyboard's autospace) is still an address.
        val joined = typed.replace(Regex("""\.\s+"""), ".")
        val t = if (!joined.contains(' ') && joined.contains('.')) joined else typed
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return t
        if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("magnet:")) return t
        if (!t.contains(' ') && HOST_LIKE.matches(t)) {
            val local = lower.startsWith("localhost") || lower.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}.*"""))
            return (if (local) "http://" else "https://") + t
        }
        return search(t, engineId)
    }

    fun search(query: String, engineId: String): String =
        engine(engineId).query.replace("%s", URLEncoder.encode(query, "UTF-8"))

    /** The search words when [url] is a results page of one of the engines (shown in the bar instead of the URL). */
    fun searchTerms(url: String): String? {
        for (e in ENGINES) {
            val prefix = e.query.substringBefore("%s")
            if (url.startsWith(prefix)) {
                val q = url.removePrefix(prefix).substringBefore('&').substringBefore('#')
                return runCatching { java.net.URLDecoder.decode(q, "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** Host without "www." for display, or the whole text when it isn't a web address. */
    fun displayHost(url: String): String {
        val host = hostOf(url) ?: return url
        return host.removePrefix("www.")
    }

    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':')
        return host.lowercase().takeIf { it.isNotEmpty() }
    }

    /** http:// pages that can be tried over https:// (not local addresses, which rarely have certificates). */
    fun httpsUpgradeOf(url: String): String? {
        if (!url.startsWith("http://", ignoreCase = true)) return null
        val host = hostOf(url) ?: return null
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".test")) return null
        if (host.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) || host.startsWith("[")) return null
        if (url.substringAfter("://").substringBefore('/').contains(':')) return null // explicit port
        return "https://" + url.substring(7)
    }

    /** True if [host] is [site] or one of its subdomains. */
    fun matchesSite(host: String, site: String) = host == site || host.endsWith(".$site")

    /** The phone's browser identity as a desktop Chrome on Linux, for desktop pages. */
    fun desktopUserAgent(mobile: String): String {
        val chrome = Regex("""Chrome/([\d.]+)""").find(mobile)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    /**
     * The page through Google Translate, into the given language (the phone's). Null for pages that can't go
     * through it: not a web page, or already a translation.
     */
    fun translateUrl(url: String, locale: java.util.Locale): String? {
        val host = hostOf(url) ?: return null
        if (!url.startsWith("http") || host.endsWith(".translate.goog") || host == "translate.google.com") return null
        val lang = when (val l = locale.language) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            "zh" -> if (locale.country in setOf("TW", "HK", "MO") || locale.script == "Hant") "zh-TW" else "zh-CN"
            "" -> "en"
            else -> l
        }
        return "https://translate.google.com/translate?sl=auto&tl=$lang&u=" + java.net.URLEncoder.encode(url, "UTF-8")
    }

    /** The address without the desktop-site switch some sites (YouTube) add, so the phone page comes back. */
    fun withoutDesktopSwitch(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val end = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val params = url.substring(q + 1, end).split('&').filter { it.isNotEmpty() && it != "app=desktop" }
        return url.substring(0, q) + (if (params.isEmpty()) "" else "?" + params.joinToString("&")) + url.substring(end)
    }
}
