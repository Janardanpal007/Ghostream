package com.fastdownloader.app.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.fastdownloader.app.engine.AdBlock
import com.fastdownloader.app.engine.Engine

/** A video or audio file the page loaded. */
data class Media(val url: String, val kind: String, val referer: String)

/** What the WebView's background threads need to know about the tab's page. */
class PageState {
    @Volatile var topUrl: String = ""
    @Volatile var lastTouch: Long = 0

    /** Ad and tracker blocking is on, and the user hasn't turned it off for this site with Shields. */
    fun blocking(url: String = topUrl): Boolean {
        val s = Engine.settings
        if (!s.blockAds) return false
        val host = AdBlock.hostOf(url.lowercase()) ?: return true
        return s.adblockAllow.none { Omnibox.matchesSite(host, it) }
    }
}

/**
 * One browser tab. The fields are Compose state, so the screen follows the page as it loads.
 * The WebView is made when the tab is first shown, and may be dropped for tabs not used in a while
 * (the tab keeps its address and reloads it when shown again).
 */
private const val MAX_BACK = 50

class BrowserTab(
    val id: Long,
    val private: Boolean,
    url: String,
    title: String,
    desktop: Boolean,
) {
    /** The page's address, "" for the start page. */
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    /** The start page is showing (the WebView keeps the page that was open, for Back). */
    var home by mutableStateOf(url.isEmpty())
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    /**
     * Pages before the WebView's own history, oldest first: kept when the WebView is dropped or the app was closed
     * (a new WebView starts with no history), so Back keeps working. See [backUrls].
     */
    var olderBack by mutableStateOf<List<String>>(emptyList())
    /** Opening a page from [olderBack]: its WebView history is cleared once it loads, so Back goes further back. */
    internal var clearHistoryOnLoad = false
    var canForward by mutableStateOf(false)
    var blocked by mutableIntStateOf(0)
    var desktop by mutableStateOf(desktop)
    /** Page zoom in percent, kept while the tab is open (100 = as the site made it). */
    var zoom by mutableIntStateOf(100)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    var favicon by mutableStateOf<ImageBitmap?>(null)
    var findCount by mutableIntStateOf(0)
    var findIndex by mutableIntStateOf(0)
    val media = mutableStateListOf<Media>()
    val videosByFrame = mutableStateMapOf<String, Int>()
    /** A video on the page is playing (for picture-in-picture when the user leaves the app). */
    var playing by mutableStateOf(false)
    /** What the page's sign-in form asks for: "username", "password" or "" (none). */
    var loginForm by mutableStateOf("")
    /** A sign-in the user just sent, offered for saving. */
    var savePrompt by mutableStateOf<PendingLogin?>(null)
    /** The fill offer was closed for this page. */
    var fillDismissed by mutableStateOf(false)
    /** Raised when the user taps into a sign-in box: the saved sign-in is offered with the fingerprint, once per page. */
    var fillRequest by mutableIntStateOf(0)
    internal var fillAsked = false
    internal var fillHandled = 0
    /** A password export the page made (site it came from, the file's text), offered for importing. */
    var passwordFile by mutableStateOf<Pair<String, String>?>(null)
    internal var pendingLogin: PendingLogin? = null
    internal var lastIdentifier: Triple<String, String, Long>? = null

    /** Once the page has moved on from its password step, the sign-in it sent is offered for saving. */
    fun offerSave() {
        val p = pendingLogin ?: return
        pendingLogin = null
        if (!private && System.currentTimeMillis() - p.at < 3 * 60_000) savePrompt = p
    }
    val state = PageState().also { it.topUrl = url }

    /** Opened from a link in this other tab: Back on the first page closes this tab and returns there. */
    var parentId: Long? = null
    /** An http:// address now being tried over https:// (to fall back to if that fails). */
    var upgradedFrom: String? = null
    var lastUsed = System.currentTimeMillis()
    var web: WebView? = null

    val secure get() = url.startsWith("https://")

    fun cookies(): CookieManager = if (private) PrivateProfile.cookies() else CookieManager.getInstance()

    /** A small picture of the page for the tab grid. */
    fun capture() {
        val w = web ?: return
        if (home || w.width == 0 || w.height == 0) return
        runCatching {
            val scale = 0.35f
            val bmp = Bitmap.createBitmap((w.width * scale).toInt().coerceAtLeast(1), (w.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            canvas.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(canvas)
            thumbnail = bmp.asImageBitmap()
        }
    }

    /** Every page Back can go to, oldest first. */
    fun backUrls(): List<String> {
        val list = web?.copyBackForwardList()
        val own = if (list == null) emptyList() else (0 until list.currentIndex).mapNotNull { list.getItemAtIndex(it)?.url }
        return (olderBack + own).filter { it.startsWith("http") }.takeLast(MAX_BACK)
    }

    fun destroyWeb() {
        val w = web ?: return
        olderBack = backUrls()
        web = null
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        w.stopLoading()
        w.destroy()
    }

    /** The whole page scaled and laid out again, like zoom on a computer: zoomed out, more of it fits. */
    fun applyZoom() {
        val z = if (zoom == 100) "''" else "'" + zoom / 100.0 + "'"
        web?.evaluateJavascript("document.documentElement && (document.documentElement.style.zoom = $z)", null)
    }

    fun clearPage() {
        media.clear()
        videosByFrame.clear()
        playing = false
        blocked = 0
        offerSave()
        loginForm = ""
        fillDismissed = false
        fillAsked = false
    }
}

/**
 * Private tabs get their own WebView profile: separate cookies, storage and cache, deleted when the last
 * private tab closes. Needs a recent Android System WebView (MULTI_PROFILE); without it private tabs still
 * keep no history or saved tabs, but share cookies with normal tabs (shown on the private start page).
 */
object PrivateProfile {
    const val NAME = "fd-private"

    val supported: Boolean by lazy { runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false) }

    fun cookies(): CookieManager =
        if (supported) ProfileStore.getInstance().getOrCreateProfile(NAME).cookieManager else CookieManager.getInstance()

    /** Everything the private tabs stored. Only possible while no WebView uses the profile. */
    fun wipe(retry: Boolean = true) {
        if (!supported) return
        val ok = runCatching {
            val store = ProfileStore.getInstance()
            !store.allProfileNames.contains(NAME) || store.deleteProfile(NAME)
        }.getOrDefault(false)
        // A just-closed WebView lets go of the profile a moment later.
        if (!ok && retry) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ wipe(false) }, 1500)
    }
}
