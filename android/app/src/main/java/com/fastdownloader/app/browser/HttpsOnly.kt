package com.fastdownloader.app.browser

import android.net.Uri
import android.text.TextUtils
import android.webkit.WebView
import com.fastdownloader.app.engine.Engine

/**
 * HTTPS-only mode: an http:// page that has no secure version is not opened silently. The tab shows a warning
 * page ("This site doesn't support a secure connection") with Go back and Continue to site. Continue lets that
 * site load over http:// until the app closes.
 */
object HttpsOnly {
    /** Link scheme of the warning page's buttons; handled by the tab, never opened. */
    const val SCHEME = "ghostream-https"
    private const val ACTION_BACK = "back"
    private const val ACTION_CONTINUE = "continue"
    private const val PARAM_URL = "u"
    private val allowedHosts = java.util.Collections.synchronizedSet(HashSet<String>())
    private val PRIVATE_IPV4 = Regex("""^(10\.|127\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.).*""")

    /** [url] is plain http:// and the user hasn't allowed or exempted its site, with the mode on. */
    fun needsWarning(url: String): Boolean {
        val s = Engine.settings
        if (!s.httpsOnly || !url.startsWith("http://", ignoreCase = true)) return false
        val host = Omnibox.hostOf(url) ?: return false
        if (host in allowedHosts || isLocal(host)) return false
        return s.httpsOffSites.none { Omnibox.matchesSite(host, it) }
    }

    /** Addresses that only exist on the phone's own network can't have a certificate. */
    private fun isLocal(host: String) =
        host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost") || PRIVATE_IPV4.matches(host)

    private fun page(url: String): String {
        val safe = TextUtils.htmlEncode(url)
        val host = TextUtils.htmlEncode(Omnibox.hostOf(url) ?: url)
        val cont = "$SCHEME://$ACTION_CONTINUE?$PARAM_URL=${Uri.encode(url)}"
        return """<!doctype html><html><head><meta charset="utf-8"><title>Connection not secure</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>:root{color-scheme:light dark}body{font:16px system-ui,sans-serif;margin:0;padding:48px 24px;max-width:480px;margin:auto}
h1{font-size:22px}p{line-height:1.5;opacity:.8;word-break:break-all}a{display:block;text-align:center;padding:14px;border-radius:12px;margin-top:12px;text-decoration:none;font-weight:600}
.go{background:#2563eb;color:#fff}.no{border:1px solid #8888;color:inherit}</style></head><body>
<h1>This site doesn't support a secure connection</h1>
<p>$host only offers http://, so what you send and receive could be read by others on the network.</p>
<p>$safe</p>
<a class="go" href="$SCHEME://$ACTION_BACK">Go back</a>
<a class="no" href="$cont">Continue to site</a></body></html>"""
    }

    /** Shows the warning for [url] in this tab instead of loading it. */
    fun show(tab: BrowserTab, view: WebView, url: String) {
        tab.interstitialFor = url
        tab.home = false
        tab.loading = false
        view.loadDataWithBaseURL(url, page(url), "text/html", "utf-8", url)
    }

    /** A tap on the warning page's buttons. Returns true when [uri] was one of them. */
    fun handleLink(tab: BrowserTab, view: WebView, uri: Uri, open: (String) -> Unit): Boolean {
        if (uri.scheme != SCHEME) return false
        val shown = tab.interstitialFor ?: return true // not from a warning page we showed: ignore
        when (uri.host) {
            ACTION_BACK -> {
                tab.interstitialFor = null
                if (view.canGoBack()) view.goBack() else { tab.home = true; tab.url = "" }
            }
            ACTION_CONTINUE -> {
                val target = uri.getQueryParameter(PARAM_URL)
                if (target == shown) {
                    tab.interstitialFor = null
                    Omnibox.hostOf(target)?.let { allowedHosts += it }
                    open(target)
                }
            }
        }
        return true
    }
}
