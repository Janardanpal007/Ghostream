package com.fastdownloader.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fastdownloader.app.BuildConfig
import com.fastdownloader.app.engine.AdBlock
import com.fastdownloader.app.engine.Categories
import com.fastdownloader.app.engine.DeviceProfile
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Filters
import com.fastdownloader.app.engine.Links
import com.fastdownloader.app.engine.Net
import com.fastdownloader.app.engine.Redirects
import com.fastdownloader.app.engine.Turbo
import com.fastdownloader.app.ui.AddRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream

/** A tap on a page's Download button. [page] is the page or frame the video is in. */
data class VideoClick(val page: String, val top: Boolean, val src: String)

/** A link or picture the user held a finger on. */
data class LinkTarget(val link: String?, val image: String?, val pageUrl: String)

/** What a tab's WebView asks of the browser screen. */
interface TabHost {
    val pageScript: String
    val documentStart: Boolean
    fun download(tab: BrowserTab, req: AddRequest)
    fun openTab(url: String, from: BrowserTab)
    fun showFullScreen(view: View, callback: WebChromeClient.CustomViewCallback)
    fun hideFullScreen()
    fun longPress(tab: BrowserTab, target: LinkTarget)
    fun videoClick(tab: BrowserTab, click: VideoClick)
    fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    /** The tab moved to another page (for the saved session). */
    fun navigated(tab: BrowserTab)
}

private val MEDIA = Regex("""\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|opus|flac)(\?|#|$)""", RegexOption.IGNORE_CASE)

/** Sites whose https:// version failed this run: they're loaded over http:// as asked. */
private val httpsFailed = java.util.Collections.synchronizedSet(HashSet<String>())

/**
 * The answer to a blocked request: "204 No Content", readable from the page's origin. A browser extension
 * makes blocked requests fail; WebView can't, and an empty 200 reply or a cross-origin error breaks
 * sites like YouTube that expect their tracking calls to just fail quietly.
 */
private fun blockedResponse(req: WebResourceRequest, pageUrl: String): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", 204, "No Content", corsHeaders(req, pageUrl), ByteArrayInputStream(ByteArray(0)))

private fun corsHeaders(req: WebResourceRequest, pageUrl: String): Map<String, String> {
    val origin = req.requestHeaders["Origin"] ?: req.requestHeaders["origin"]
        ?: pageUrl.substringBefore("://", "").let { scheme -> if (scheme.isEmpty()) "*" else "$scheme://" + (AdBlock.hostOf(pageUrl) ?: "") }
    return mapOf(
        "Access-Control-Allow-Origin" to origin,
        "Access-Control-Allow-Credentials" to "true",
        "Cache-Control" to "no-store",
    )
}

private val redirectBytes = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

/** A cross-site move of the tab this soon after the page opened a new tab is a tab-under. */
private const val TAB_UNDER_MS = 1_500L
/** More than this many alert/confirm/prompt boxes within [DIALOG_WINDOW_MS] are dismissed. */
private const val DIALOGS_ALLOWED = 3
private const val DIALOG_WINDOW_MS = 60_000L // people dismiss them at reading speed, so the window is long

/**
 * A blocked request whose rule names a stand-in ($redirect=): answer with it, so pages that check their ad
 * script loaded keep working. Null when there is no stand-in; then the request is just blocked.
 */
private fun redirectResponse(ctx: Context, name: String, req: WebResourceRequest, pageUrl: String): WebResourceResponse? {
    if (name.isEmpty()) return null
    val r = Redirects.find(name) ?: return null
    val bytes = r.bytes ?: r.asset?.let { file ->
        redirectBytes[file] ?: runCatching { ctx.assets.open("redirects/$file").use { it.readBytes() } }
            .onFailure { Log.w("TabWebView", "stand-in $file missing", it) }
            .getOrNull()?.also { redirectBytes[file] = it }
    } ?: return null
    return WebResourceResponse(r.mime, "utf-8", 200, "OK", corsHeaders(req, pageUrl), ByteArrayInputStream(bytes))
}

/** Resource type for filter rules like $script or $image. WebView doesn't say, so it's worked out. */
private fun typeOf(req: WebResourceRequest): AdBlock.Type {
    if (req.isForMainFrame) return AdBlock.Type.DOCUMENT
    val accept = req.requestHeaders["Accept"]?.lowercase() ?: ""
    val ext = (req.url.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
    return when {
        accept.startsWith("text/html") -> AdBlock.Type.SUBDOCUMENT
        accept.startsWith("text/css") || ext == "css" -> AdBlock.Type.STYLESHEET
        accept.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp") -> AdBlock.Type.IMAGE
        ext == "js" || ext == "mjs" -> AdBlock.Type.SCRIPT
        ext in setOf("woff", "woff2", "ttf", "otf", "eot") -> AdBlock.Type.FONT
        ext in setOf("mp4", "webm", "m3u8", "mpd", "ts", "m4s", "mp3", "m4a", "aac", "ogg") -> AdBlock.Type.MEDIA
        accept.contains("json") -> AdBlock.Type.XHR
        else -> AdBlock.Type.OTHER
    }
}

/** Request headers for downloading [url] with the tab's sign-in, so the server answers as it did for the page. */
fun BrowserTab.headersFor(url: String, referer: String?): Map<String, String> {
    val h = linkedMapOf("User-Agent" to (web?.settings?.userAgentString ?: Net.userAgent))
    cookies().getCookie(url)?.let { h["Cookie"] = it }
    if (referer != null) h["Referer"] = referer
    return h
}

/** The calls the page script (assets/page.js) can make. Every frame of every page can call these. */
private class PageBridge(private val tab: BrowserTab, private val host: TabHost) {
    private val main = Handler(Looper.getMainLooper())
    private val state get() = tab.state

    /** uBlock scriptlets for the page or frame at [url], as JSON (assets/scriptlets.js runs them). */
    @JavascriptInterface
    fun scriptlets(url: String, top: Boolean): String =
        if (state.blocking(if (top) url else state.topUrl)) Filters.engine?.scriptletsFor(url) ?: "[]" else "[]"

    @JavascriptInterface
    fun popupBlocked(@Suppress("UNUSED_PARAMETER") url: String) {
        main.post { tab.blocked++; BrowserStore.countBlocked() }
    }

    /** A video playlist the page loaded, recognised by its content (sites often hide the usual .m3u8 name). */
    @JavascriptInterface
    fun media(url: String, kind: String, frame: String) {
        if (!url.startsWith("http") || kind !in setOf("m3u8", "mpd")) return
        main.post { if (tab.media.none { it.url == url }) tab.media.add(Media(url, kind, frame)) }
    }

    /** The page asks for a user name or a password (or no longer does). */
    @JavascriptInterface
    fun loginForm(url: String, kind: String) {
        main.post {
            if (Passwords.siteOf(url) != Passwords.siteOf(tab.url)) return@post
            val was = tab.loginForm
            tab.loginForm = kind
            if (was == "password" && kind != "password") tab.offerSave()
        }
    }

    /** The user tapped into a sign-in box. */
    @JavascriptInterface
    fun loginFocus(url: String) {
        main.post {
            if (tab.fillAsked || tab.fillDismissed || Passwords.siteOf(url) != Passwords.siteOf(tab.url)) return@post
            if (Passwords.forSite(tab.url).isEmpty()) return@post
            tab.fillAsked = true
            tab.fillRequest++
        }
    }

    /** A user name typed on a page that asks for the password on a later step. */
    @JavascriptInterface
    fun loginIdentifier(url: String, value: String) {
        val site = Passwords.siteOf(url) ?: return
        main.post { tab.lastIdentifier = Triple(site, value.take(200), System.currentTimeMillis()) }
    }

    /** A password export made in the page (see assets/page.js, part 5c). */
    @JavascriptInterface
    fun passwordFile(url: String, @Suppress("UNUSED_PARAMETER") name: String, text: String) {
        val site = Passwords.siteOf(url) ?: return
        if (text.length > 5_000_000) return
        main.post { tab.passwordFile = site to text }
    }

    /** The user signed in on the page: kept until the page moves on, then offered for saving. */
    @JavascriptInterface
    fun loginSubmitted(url: String, username: String, password: String) {
        main.post {
            if (tab.private || Passwords.siteOf(url) != Passwords.siteOf(tab.url)) return@post
            val site = Passwords.siteOf(url)
            val user = username.ifBlank {
                tab.lastIdentifier?.takeIf { it.first == site && System.currentTimeMillis() - it.third < 10 * 60_000 }?.second.orEmpty()
            }
            tab.pendingLogin = Passwords.pending(url, user.take(200), password.take(500))
        }
    }

    @JavascriptInterface
    fun playing(on: Boolean) {
        main.post { tab.playing = on }
    }

    @JavascriptInterface
    fun hideShorts(): Boolean = Engine.settings.hideShorts

    /** YouTube ad removal is part of ad blocking: off when Shields are down for the site. */
    @JavascriptInterface
    fun youtubeAdsOff(): Boolean = state.blocking()

    /** The quality YouTube should play at, for the current network: "highest", "hd1440", "hd1080", "hd720", or "" to let YouTube choose. */
    @JavascriptInterface
    fun youtubeQuality(): String {
        val settings = Engine.settings
        return when (val choice = Turbo.youtubeChoice(if (Engine.metered) settings.youtubeQualityMobile else settings.youtubeQuality)) {
            "auto" -> ""
            "wifi" -> DeviceProfile.wifiQuality
            else -> choice
        }
    }

    @JavascriptInterface
    fun cosmeticBase(url: String): String =
        if (state.blocking()) Filters.engine?.cosmeticBase(url) ?: "" else ""

    @JavascriptInterface
    fun proceduralFor(url: String): String =
        if (state.blocking()) Filters.engine?.proceduralFor(url) ?: "[]" else "[]"

    @JavascriptInterface
    fun cosmeticFor(url: String, idsAndClasses: String): String =
        if (state.blocking()) Filters.engine?.cosmeticFor(url, idsAndClasses.take(200_000)) ?: "" else ""

    /** Only acted on right after a real tap, so a page can't open download panels by itself. */
    @JavascriptInterface
    fun video(json: String) {
        if (System.currentTimeMillis() - state.lastTouch > 3000) return
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        val click = VideoClick(
            page = o["page"]?.jsonPrimitive?.contentOrNull ?: return,
            top = o["top"]?.jsonPrimitive?.booleanOrNull ?: true,
            src = o["src"]?.jsonPrimitive?.contentOrNull ?: "",
        )
        main.post { host.videoClick(tab, click) }
    }

    @JavascriptInterface
    fun found(count: Int, url: String, top: Boolean) {
        main.post {
            val frame = if (top) "" else url
            if (count > 0) tab.videosByFrame[frame] = count else tab.videosByFrame.remove(frame)
        }
    }
}

/** Per-site Shields that are WebView settings: scripts and third-party cookies, set before each page loads. */
fun BrowserTab.applySitePolicy(url: String) {
    val w = web ?: return
    val s = Engine.settings
    val host = Omnibox.hostOf(url) ?: return
    w.settings.javaScriptEnabled = s.scriptBlockSites.none { Omnibox.matchesSite(host, it) }
    val cookies = cookies()
    cookies.setAcceptCookie(s.cookiePolicy != "none")
    val thirdParty = s.cookiePolicy == "all" || (s.cookiePolicy != "none" && s.thirdPartyCookieSites.any { Omnibox.matchesSite(host, it) })
    cookies.setAcceptThirdPartyCookies(w, thirdParty)
}

private fun httpsAllowed(url: String): String? {
    val s = Engine.settings
    if (!s.httpsUpgrade) return null
    val host = Omnibox.hostOf(url) ?: return null
    if (host in httpsFailed || s.httpsOffSites.any { Omnibox.matchesSite(host, it) }) return null
    return Omnibox.httpsUpgradeOf(url)
}

/** Opens [url] in the tab: Shields first, then https:// for http:// addresses when the site has it. */
fun BrowserTab.load(url: String) {
    val w = web ?: return
    home = false
    val upgraded = httpsAllowed(url)
    val target = upgraded ?: url
    upgradedFrom = if (upgraded != null) url else null
    if (upgraded != null) BrowserStore.countHttpsUpgrade()
    applySitePolicy(target)
    if (target != this.url) clearPage()
    this.url = target
    state.topUrl = target
    w.loadUrl(target)
}

/** Back-to-http when the https:// version of an upgraded page doesn't work. */
private fun BrowserTab.httpsFallback(view: WebView): Boolean {
    val original = upgradedFrom ?: return false
    upgradedFrom = null
    Omnibox.hostOf(original)?.let { httpsFailed += it }
    applySitePolicy(original)
    view.loadUrl(original)
    return true
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "RequiresFeature")
fun BrowserTab.createWebView(ctx: Context, host: TabHost): WebView {
    web?.let { return it }
    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    val tab = this
    val w = WebView(ctx)
    // A private tab's profile must be set before anything else touches the WebView.
    if (private && PrivateProfile.supported) {
        PrivateProfile.cookies() // creates the profile if needed
        WebViewCompat.setProfile(w, PrivateProfile.NAME)
    }
    web = w
    w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    val s = Engine.settings
    with(w.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = true
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        textZoom = s.textZoom
        // New windows go through onCreateWindow below, where pop-ups and pop-unders are stopped.
        setSupportMultipleWindows(true)
        if (private) cacheMode = WebSettings.LOAD_NO_CACHE
    }
    if (desktop) w.setDesktop(true)
    w.addJavascriptInterface(PageBridge(tab, host), "__fdBridge")
    // In every page and frame, before the page's own scripts (falls back to after load on old WebViews).
    if (host.documentStart) WebViewCompat.addDocumentStartJavaScript(w, host.pageScript, setOf("*"))
    w.setOnTouchListener { _, ev ->
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_UP) state.lastTouch = System.currentTimeMillis()
        false
    }
    // Holding a link or picture: the browser's own menu (open in a new tab, download…). Text keeps the usual selection.
    w.setOnLongClickListener {
        val hit = w.hitTestResult
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> { host.longPress(tab, LinkTarget(hit.extra, null, url)); true }
            WebView.HitTestResult.IMAGE_TYPE -> { host.longPress(tab, LinkTarget(null, hit.extra, url)); true }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val image = hit.extra
                val msg = Handler(Looper.getMainLooper()) { m ->
                    host.longPress(tab, LinkTarget(m.data.getString("url"), image, url)); true
                }.obtainMessage()
                w.requestFocusNodeHref(msg)
                true
            }
            else -> false
        }
    }
    // When this page last opened a new tab, for spotting tab-unders (see shouldOverrideUrlLoading).
    var windowOpenedAt = 0L
    w.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val s2 = request.url.toString()
            if (!request.isForMainFrame && state.blocking()) {
                val engine = Filters.engine
                val verdict = engine?.blockOrRedirect(s2, state.topUrl, typeOf(request))
                if (verdict != null) {
                    view.post { blocked++; BrowserStore.countBlocked() }
                    return redirectResponse(view.context, verdict, request, state.topUrl) ?: blockedResponse(request, state.topUrl)
                }
            }
            MEDIA.find(s2)?.let { m ->
                val kind = m.groupValues[1].lowercase()
                // The page or frame that asked for it: some servers only answer requests from their own player.
                val referer = request.requestHeaders["Referer"] ?: request.requestHeaders["referer"] ?: state.topUrl
                view.post {
                    // Skip the many small pieces a stream is made of; keep its playlist.
                    if (media.none { it.url == s2 } && !(kind == "mp4" && s2.contains("range=", true))) {
                        media.add(Media(s2, kind, referer))
                    }
                }
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            if (Links.isMagnet(target)) { host.download(tab, AddRequest(target)); return true }
            if (request.url.scheme !in setOf("http", "https")) {
                // intent:, market:, tel: and the like open in the app that handles them, when the user tapped.
                // An ad that tries to open another app is stopped, as the ad blocker stops ad pop-ups.
                val adOpensApp = state.blocking() && Filters.engine?.shouldBlock(target, state.topUrl, AdBlock.Type.POPUP) == true
                if (adOpensApp) { blocked++; BrowserStore.countBlocked(); return true }
                if (request.hasGesture()) runCatching {
                    val intent = if (target.startsWith("intent:")) android.content.Intent.parseUri(target, android.content.Intent.URI_INTENT_SCHEME)
                    else android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                    intent.addCategory(android.content.Intent.CATEGORY_BROWSABLE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.component = null
                    intent.selector = null
                    view.context.startActivity(intent)
                }
                return true
            }
            if (!request.isForMainFrame) return false
            // Pop-ups and redirects to ad pages that the user didn't tap on.
            if (!request.hasGesture() && state.blocking() &&
                Filters.engine?.shouldBlock(target, state.topUrl, AdBlock.Type.POPUP) == true
            ) {
                blocked++
                BrowserStore.countBlocked()
                return true
            }
            // Tab-under: the page opened a new tab and, right after, sends this one to another site (the ad).
            val site = { u: String -> AdBlock.hostOf(u.lowercase())?.let { AdBlock.baseDomain(it) } }
            val sameSite = { a: String, b: String -> site(a) == site(b) }
            if (state.blocking() && android.os.SystemClock.uptimeMillis() - windowOpenedAt < TAB_UNDER_MS && !sameSite(target, state.topUrl)) {
                blocked++
                BrowserStore.countBlocked()
                return true
            }
            // Strip click-tracking parameters (utm_source, fbclid, $removeparam rules) from links the page opens.
            // loadUrl() doesn't come back through here, so this can't loop.
            if (!request.isRedirect && request.method == "GET" && state.blocking(target)) {
                Filters.engine?.cleanUrl(target)?.let { clean -> view.loadUrl(clean); return true }
            }
            // A link to a file: straight to the download panel instead of a blank page.
            if (Engine.settings.detectFileLinks && isFileLink(target)) {
                host.download(tab, AddRequest(target, headersFor(target, url)))
                return true
            }
            // https:// first. A site that sends its https:// page back to http:// is left on http://.
            val host2 = Omnibox.hostOf(target)
            if (target.startsWith("http://") && upgradedFrom != null && Omnibox.hostOf(upgradedFrom!!) == host2) {
                host2?.let { httpsFailed += it }
                upgradedFrom = null
            } else {
                httpsAllowed(target)?.let { up ->
                    upgradedFrom = target
                    BrowserStore.countHttpsUpgrade()
                    applySitePolicy(up)
                    view.loadUrl(up)
                    return true
                }
            }
            applySitePolicy(target)
            return false
        }

        override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
            loading = true
            home = false
            if (pageUrl != url) { clearPage(); tab.favicon = null }
            url = pageUrl
            state.topUrl = pageUrl
            if (!host.documentStart) view.evaluateJavascript(host.pageScript, null)
        }

        override fun onPageFinished(view: WebView, pageUrl: String) {
            loading = false
            progress = 100
            if (clearHistoryOnLoad) { clearHistoryOnLoad = false; view.clearHistory() }
            canBack = view.canGoBack()
            canForward = view.canGoForward()
            if (upgradedFrom != null && pageUrl.startsWith("https://")) upgradedFrom = null
            if (zoom != 100) applyZoom()
            if (!host.documentStart) view.evaluateJavascript(host.pageScript, null)
            view.title?.takeIf { it.isNotBlank() }?.let { title = it }
            if (!private) BrowserStore.updateTitle(pageUrl, title)
            host.navigated(tab)
        }

        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
            // Sites like YouTube change pages without reloading.
            if (pageUrl != url) media.clear()
            url = pageUrl
            state.topUrl = pageUrl
            canBack = view.canGoBack()
            canForward = view.canGoForward()
            if (!private && !isReload) BrowserStore.addVisit(pageUrl, view.title ?: "")
            host.navigated(tab)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && upgradedFrom != null && request.url.scheme == "https") httpsFallback(view)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (upgradedFrom != null && error.url?.startsWith("https://") == true) httpsFallback(view)
        }
    }
    w.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, t: String?) {
            if (!t.isNullOrBlank()) {
                title = t
                if (!private) BrowserStore.updateTitle(url, t)
            }
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            tab.favicon = icon?.asImageBitmap()
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = host.showFullScreen(view, callback)

        override fun onHideCustomView() = host.hideFullScreen()

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
            host.chooseFiles(callback, params)

        // Scam pages that show alert after alert ("You won!") to trap the user: past a few, they're dismissed.
        private val dialogTimes = ArrayDeque<Long>()
        private fun dialogFlood(): Boolean {
            if (!state.blocking()) return false
            val now = android.os.SystemClock.uptimeMillis()
            while (dialogTimes.isNotEmpty() && now - dialogTimes.first() > DIALOG_WINDOW_MS) dialogTimes.removeFirst()
            dialogTimes.addLast(now)
            return dialogTimes.size > DIALOGS_ALLOWED
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
            if (!dialogFlood()) return false
            result.confirm()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
            if (!dialogFlood()) return false
            result.cancel()
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean {
            if (!dialogFlood()) return false
            result.cancel()
            return true
        }

        // A page opening a new window. A link the user tapped opens in a new tab. Windows a page opens by itself,
        // or from a tap somewhere else on the page (the usual "pop-under" ad trick), only open when they stay on
        // the same site.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val blocking = state.blocking()
            fun open(target: String) {
                if (blocking && Filters.engine?.shouldBlock(target, state.topUrl, AdBlock.Type.POPUP) == true) { blocked++; BrowserStore.countBlocked() }
                else { windowOpenedAt = android.os.SystemClock.uptimeMillis(); host.openTab(target, tab) }
            }
            val hit = view.hitTestResult
            val link = hit.extra?.takeIf {
                hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            }
            if (isUserGesture && link != null && link.startsWith("http")) { open(link); return false }
            if (!isUserGesture && blocking) { blocked++; BrowserStore.countBlocked(); return false }
            // Not a link: let the window start loading in a hidden view to see where it goes.
            val probe = WebView(view.context)
            if (private && PrivateProfile.supported) WebViewCompat.setProfile(probe, PrivateProfile.NAME)
            var decided = false
            fun decide(target: String) {
                if (decided || target.isEmpty() || target == "about:blank") return
                decided = true
                probe.stopLoading()
                val site = { u: String -> AdBlock.hostOf(u.lowercase())?.let { AdBlock.baseDomain(it) } }
                if (!blocking || site(target) == site(state.topUrl)) open(target) else { blocked++; BrowserStore.countBlocked() }
                probe.post { probe.destroy() }
            }
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { decide(r.url.toString()); return true }
                override fun onPageStarted(v: WebView, u: String, favicon: Bitmap?) { decide(u) }
            }
            probe.postDelayed({ if (!decided) { decided = true; probe.destroy() } }, 10_000)
            (resultMsg.obj as WebView.WebViewTransport).webView = probe
            resultMsg.sendToTarget()
            return true
        }
    }
    w.setFindListener { active, count, done -> if (done) { findIndex = if (count == 0) 0 else active + 1; findCount = count } }
    // Links the page offers as downloads come here instead of the system downloader.
    w.setDownloadListener { dl, ua, disposition, mime, length ->
        // The address was a file, not a page: the tab stays on the page it was showing.
        val shown = w.url?.takeIf { it.startsWith("http") && it != dl }
        if (shown != null && url != shown) { url = shown; state.topUrl = shown }
        loading = false
        upgradedFrom = null
        val headers = headersFor(dl, shown ?: url).toMutableMap()
        if (!ua.isNullOrBlank()) headers["User-Agent"] = ua
        host.download(tab, AddRequest(dl, headers))
    }
    return w
}

/** A main-frame link that is a file to save rather than a page (pictures and text still open in the tab). */
private fun isFileLink(url: String): Boolean {
    if (!Categories.looksLikeFileUrl(url)) return false
    val name = Uri.parse(url).lastPathSegment ?: return false
    val cat = Categories.forName(name)
    return cat != Categories.IMAGES && Categories.extension(name) !in setOf("txt", "csv", "svg", "json")
}

/** The phone's own client hints, kept from before any tab asked for the desktop site. */
private var mobileHints: UserAgentMetadata? = null

/**
 * Desktop site on or off. Besides the user agent, the client hints (Sec-CH-UA-Mobile, navigator.userAgentData) say
 * "desktop Linux" too: a desktop user agent with phone hints is what "I am human" checks such as Cloudflare's
 * catch as a bot and keep failing.
 */
fun WebView.setDesktop(on: Boolean) {
    settings.userAgentString = if (on) Omnibox.desktopUserAgent(WebSettings.getDefaultUserAgent(context)) else null
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
    runCatching {
        val current = WebSettingsCompat.getUserAgentMetadata(settings)
        val phone = mobileHints ?: current.also { mobileHints = it }
        WebSettingsCompat.setUserAgentMetadata(
            settings,
            if (!on) phone
            else UserAgentMetadata.Builder(phone)
                .setPlatform("Linux").setPlatformVersion("").setModel("").setMobile(false)
                .setArchitecture("x86").setBitness(64).setFormFactors(listOf("Desktop"))
                .build(),
        )
    }
}
