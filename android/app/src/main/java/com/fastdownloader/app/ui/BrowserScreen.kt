package com.fastdownloader.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import java.util.Locale
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewFeature
import com.fastdownloader.app.browser.BrowserStore
import com.fastdownloader.app.browser.BrowserTab
import com.fastdownloader.app.browser.LinkTarget
import com.fastdownloader.app.browser.setDesktop
import com.fastdownloader.app.browser.Omnibox
import com.fastdownloader.app.browser.TabHost
import com.fastdownloader.app.browser.TabManager
import com.fastdownloader.app.browser.VideoClick
import com.fastdownloader.app.browser.applySitePolicy
import com.fastdownloader.app.browser.headersFor
import com.fastdownloader.app.browser.load
import com.fastdownloader.app.engine.AdBlock
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Filters
import com.fastdownloader.app.engine.Links
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Browser state the rest of the app's screen follows. */
object BrowserChrome {
    /** A video fills the screen (the phone is sideways, or picture-in-picture): the app hides its own bars. */
    var videoMode by mutableStateOf(false)
    /** A video the page put in full screen: its view and how to tell the page it has left full screen. */
    var fullScreen by mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null)
    /** The app is a small picture-in-picture window. */
    var pip by mutableStateOf(false)
    /** The download sheet over the browser (opened when a download starts from a page). */
    var downloadsSheet by mutableStateOf(false)
    /** The bottom bar's color, for the strip under it kept clear for the system's gesture handle. */
    var barColor by mutableStateOf<Color?>(null)
    /** A video plays in the tab on screen. */
    var playing = false
    /** The WebView on screen, for the video controls. */
    var activeWeb: WebView? = null
    /** A link another app asked to open: shown in a new tab. */
    var openUrl by mutableStateOf<String?>(null)
    /** Set by MainActivity: shows the system file picker for a page's upload button. */
    var fileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
}

/** Pages over the browser. */
enum class LibraryPage { BOOKMARKS, HISTORY }

@SuppressLint("RequiresFeature")
@Composable
fun BrowserScreen(visible: Boolean, onDownload: (AddRequest) -> Unit, onDownloads: () -> Unit, onSettings: () -> Unit) {
    val c = colors
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by Engine.settingsFlow.collectAsState()
    val startDownload by rememberUpdatedState(onDownload)

    var showTabs by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showShields by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf<LibraryPage?>(null) }
    var linkTarget by remember { mutableStateOf<Pair<BrowserTab, LinkTarget>?>(null) }
    var reader by remember { mutableStateOf<ReaderDoc?>(null) }
    var finding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(TextFieldValue("")) }
    val addressFocus = remember { FocusRequester() }

    // Build the blocker from the filter lists, and refresh them if they're a few days old.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { if (Filters.engine == null) Filters.load(ctx) }
            runCatching { Filters.updateIfDue(ctx) }
        }
    }

    /** A Download button on a video was tapped: pick the best way to get that video. */
    fun downloadVideo(tab: BrowserTab, click: VideoClick) {
        val top = tab.state.topUrl
        val src = click.src
        // The playlist loaded by the video's own frame (players often sit in a frame of another site).
        val streams = tab.media.filter { it.kind == "m3u8" || it.kind == "mpd" }
        val stream = streams.lastOrNull { AdBlock.hostOf(it.referer) == AdBlock.hostOf(click.page) } ?: streams.lastOrNull()
        val req = when {
            // Sites yt-dlp knows (YouTube, Vimeo, X…): the page, for every quality.
            Links.isVideoPageUrl(click.page) -> AddRequest(click.page, tab.headersFor(click.page, top))
            Links.isVideoPageUrl(top) -> AddRequest(top, tab.headersFor(top, top))
            // A plain video file.
            src.startsWith("http") -> AddRequest(src, tab.headersFor(src, click.page))
            // Streamed (blob:) video: the playlist the page loaded, or else let yt-dlp read the page.
            stream != null -> AddRequest(stream.url, tab.headersFor(stream.url, stream.referer))
            else -> AddRequest(click.page, tab.headersFor(click.page, top))
        }
        startDownload(req.copy(fromBrowser = true))
    }

    // The scriptlets first: they must run before the page's own scripts.
    val script = remember {
        ctx.assets.open("scriptlets.js").bufferedReader().readText() + "\n" + ctx.assets.open("page.js").bufferedReader().readText()
    }
    val docStart = remember { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
    val managerRef = remember { arrayOfNulls<TabManager>(1) }

    val host = remember {
        object : TabHost {
            override val pageScript = script
            override val documentStart = docStart
            override fun download(tab: BrowserTab, req: AddRequest) = startDownload(req.copy(fromBrowser = true, quick = Engine.settings.quickDownload))
            override fun openTab(url: String, from: BrowserTab) {
                managerRef[0]?.open(url, from.private, parent = from)
            }
            override fun showFullScreen(view: View, callback: WebChromeClient.CustomViewCallback) {
                BrowserChrome.fullScreen?.second?.onCustomViewHidden()
                BrowserChrome.fullScreen = view to callback
            }
            override fun hideFullScreen() { BrowserChrome.fullScreen = null }
            override fun longPress(tab: BrowserTab, target: LinkTarget) { linkTarget = tab to target }
            override fun videoClick(tab: BrowserTab, click: VideoClick) = downloadVideo(tab, click)
            override fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean =
                BrowserChrome.fileChooser?.invoke(callback, params) ?: false
            override fun navigated(tab: BrowserTab) { managerRef[0]?.save() }
        }
    }
    val manager = remember { TabManager(ctx, host).also { managerRef[0] = it; BrowserStore.init(ctx); com.fastdownloader.app.browser.Passwords.init(ctx); it.restore() } }
    DisposableEffect(Unit) { onDispose { manager.destroyAll() } }

    // Links from other apps open in a new tab.
    val incoming = BrowserChrome.openUrl
    LaunchedEffect(incoming) {
        if (incoming != null) { BrowserChrome.openUrl = null; manager.open(incoming, private = false) }
    }

    val tab = manager.current
    val web = tab?.takeIf { !it.home }?.web
    BrowserChrome.activeWeb = web
    BrowserChrome.playing = visible && tab?.playing == true
    val barColor = if (tab == null || !visible || BrowserChrome.videoMode || BrowserChrome.pip || editing) null
        else if (tab.private) colors.privateBg else colors.panel
    SideEffect { BrowserChrome.barColor = barColor }

    fun navigate(t: BrowserTab, url: String) {
        manager.webOf(t)
        t.load(url)
        editing = false
        focus.clearFocus()
    }

    fun go(text: String) {
        val t = manager.current ?: return
        val url = Omnibox.resolve(text, settings.searchEngine) ?: return
        if (Links.isMagnet(url)) { startDownload(AddRequest(url, fromBrowser = true)); editing = false; focus.clearFocus(); return }
        navigate(t, url)
    }

    fun goHome(t: BrowserTab) {
        val page = settings.homepageUrl.trim()
        if (page.isNotEmpty()) navigate(t, Omnibox.resolve(page, settings.searchEngine) ?: page)
        else { t.capture(); t.home = true; finding = false }
    }

    fun back(t: BrowserTab): Boolean {
        val w = t.web
        when {
            t.home && w?.url != null -> t.home = false
            w != null && w.canGoBack() -> w.goBack()
            // The page was reloaded in a new WebView (tab unused for a while, or the app was closed): its history is ours.
            t.olderBack.isNotEmpty() -> {
                val u = t.olderBack.last()
                t.olderBack = t.olderBack.dropLast(1)
                t.clearHistoryOnLoad = true
                navigate(t, u)
            }
            t.parentId != null && manager.tabs.any { it.id == t.parentId } -> manager.close(t)
            else -> return false
        }
        return true
    }

    fun newTab(private: Boolean) {
        val t = manager.open(manager.startUrl(), private)
        showTabs = false
        if (t.url.isEmpty()) {
            // Straight to typing, as on other browsers.
            editing = true
            address = TextFieldValue("")
            scope.launch { kotlinx.coroutines.delay(150); runCatching { addressFocus.requestFocus() } }
        }
    }

    // Back: close what's open over the page first, then the page's own history.
    val canGoBack = tab != null && (tab.home && tab.web?.url != null || tab.canBack || tab.olderBack.isNotEmpty() || (tab.parentId != null && manager.tabs.any { it.id == tab.parentId }))
    BackHandler(enabled = visible && canGoBack) { tab?.let { back(it) } }
    BackHandler(enabled = visible && editing) { editing = false; focus.clearFocus() }
    BackHandler(enabled = visible && finding) { finding = false; web?.clearMatches() }

    // Phone turned sideways while a video plays: the page's player fills the screen and the app's bars and the
    // system bars step aside. Turning back (or Back) undoes it. Real full screen takes over when the page asks.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullScreen = BrowserChrome.fullScreen
    LaunchedEffect(landscape, visible, fullScreen, web, BrowserChrome.pip) {
        if (web == null) return@LaunchedEffect
        if (landscape && visible && fullScreen == null && !BrowserChrome.pip) {
            val frames = tab.videosByFrame.keys.filter { it.isNotEmpty() }
                .joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
            web.evaluateJavascript("window.__fdVideoMode ? window.__fdVideoMode(true, $frames) : ''") { r ->
                BrowserChrome.videoMode = r == "\"video\"" || r == "\"frame\""
                if (BrowserChrome.videoMode) web.evaluateJavascript(VideoCtl.SHAPE) { VideoCtl.portrait = it == "\"portrait\"" }
                if (BrowserChrome.videoMode) VideoCtl.apply(web, fit = Engine.settings.videoFit)
            }
        } else if (!BrowserChrome.pip) {
            if (fullScreen == null) web.evaluateJavascript("window.__fdVideoMode && window.__fdVideoMode(false)", null)
            BrowserChrome.videoMode = false
        }
    }
    // Picture-in-picture: the playing video is pinned over the page, so the small window shows just the video.
    LaunchedEffect(BrowserChrome.pip, web) {
        if (web == null) return@LaunchedEffect
        if (BrowserChrome.pip) {
            val frames = tab.videosByFrame.keys.filter { it.isNotEmpty() }
                .joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
            web.evaluateJavascript("window.__fdVideoMode ? window.__fdVideoMode(true, $frames, true) : ''", null)
        } else if (!landscape && BrowserChrome.fullScreen == null) {
            web.evaluateJavascript("window.__fdVideoMode && window.__fdVideoMode(false)", null)
            BrowserChrome.videoMode = false
        }
    }
    // The window takes the video's shape, and its play button turns to pause while the video plays.
    val playing = tab?.playing == true
    LaunchedEffect(playing, BrowserChrome.pip, web) {
        if (web == null) return@LaunchedEffect
        if (playing) web.evaluateJavascript(VideoCtl.SIZE) { r -> VideoCtl.sizeOf(r)?.let { VideoCtl.size = it } }
        if (BrowserChrome.pip) ctx.findActivity()?.let { act ->
            runCatching { act.setPictureInPictureParams(VideoCtl.pipParams(act, playing)) }
        }
    }
    // A video page that stays open a moment: its qualities are read now, so Download opens with them ready.
    val pageUrl = tab?.url
    LaunchedEffect(pageUrl, tab?.private) {
        val t = tab ?: return@LaunchedEffect
        val u = pageUrl ?: return@LaunchedEffect
        if (t.private || !Links.isVideoPageUrl(u)) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        com.fastdownloader.app.engine.VideoPrefetch.start(ctx, u, t.headersFor(u, u))
    }
    BackHandler(enabled = BrowserChrome.videoMode && fullScreen == null) {
        web?.evaluateJavascript("window.__fdVideoMode && window.__fdVideoMode(false)", null)
        BrowserChrome.videoMode = false
    }
    DisposableEffect(BrowserChrome.videoMode) {
        val act = ctx.findActivity()
        if (!BrowserChrome.videoMode || act == null) return@DisposableEffect onDispose {}
        val bars = WindowCompat.getInsetsController(act.window, act.window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        val cutout = if (Build.VERSION.SDK_INT >= 28) act.window.attributes.layoutInDisplayCutoutMode else 0
        if (Build.VERSION.SDK_INT >= 28) {
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose {
            bars.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= 28) act.window.attributes = act.window.attributes.apply { layoutInDisplayCutoutMode = cutout }
        }
    }

    val pageHost = tab?.takeIf { !it.home }?.url?.let { AdBlock.hostOf(it.lowercase())?.removePrefix("www.")?.removePrefix("m.") } ?: ""
    val siteAllowed = pageHost.isNotEmpty() && settings.adblockAllow.any { Omnibox.matchesSite(pageHost, it) }
    val videoCount = tab?.videosByFrame?.values?.sum() ?: 0
    val private = tab?.private == true
    // A video fills the screen or the picture-in-picture window: no browser bars.
    val chromeHidden = BrowserChrome.videoMode || BrowserChrome.pip

    Box(Modifier.fillMaxSize().background(if (private) c.privateBg else c.bg)) {
        Column(Modifier.fillMaxSize()) {
            if (!chromeHidden && tab != null) {
                AddressBar(
                    tab = tab,
                    editing = editing,
                    address = address,
                    focusRequester = addressFocus,
                    shieldsOn = settings.blockAds && !siteAllowed,
                    onAddress = { address = it },
                    onFocus = { focused ->
                        if (focused && !editing) {
                            val full = if (tab.home) "" else tab.url
                            address = TextFieldValue(full, TextRange(0, full.length))
                        }
                        editing = focused
                    },
                    onGo = { go(address.text) },
                    onReload = {
                        val w = tab.web
                        when {
                            tab.home || w == null -> Unit
                            tab.loading -> w.stopLoading()
                            else -> { tab.applySitePolicy(tab.url); w.reload() }
                        }
                    },
                    onShields = { editing = false; focus.clearFocus(); showShields = true },
                    downloads = {
                        DownloadsButton(
                            onClick = {
                                when {
                                    videoCount > 0 -> startDownload(AddRequest(tab.url, tab.headersFor(tab.url, tab.url), fromBrowser = true))
                                    tab.media.isNotEmpty() && !tab.home -> showMedia = !showMedia
                                    else -> BrowserChrome.downloadsSheet = true
                                }
                            },
                            onLongClick = onDownloads,
                            videos = videoCount,
                            found = !tab.home && (videoCount > 0 || tab.media.isNotEmpty()),
                        )
                    },
                )
                if (tab.loading && !tab.home) LoadingLine(tab.progress)
            }
            if (finding && !chromeHidden && web != null && tab != null) FindBar(tab, web) { finding = false; web.clearMatches() }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab != null) {
                    if (tab.home) {
                        HomePage(
                            private = tab.private,
                            onOpen = { url -> navigate(tab, url) },
                            onSearch = {
                                editing = true
                                address = TextFieldValue("")
                                runCatching { addressFocus.requestFocus() }
                            },
                        )
                    } else {
                        key(tab.id) {
                            // In the picture-in-picture window the page keeps a phone-sized layout, drawn smaller:
                            // players such as YouTube's pause themselves in a window that small.
                            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                                val k = if (BrowserChrome.pip) (480.dp / maxWidth).coerceAtLeast(1f) else 1f
                                AndroidView(
                                    factory = { manager.webOf(tab).also { (it.parent as? ViewGroup)?.removeView(it) } },
                                    modifier = if (k == 1f) Modifier.fillMaxSize()
                                    else Modifier.requiredSize(maxWidth * k, maxHeight * k).graphicsLayer(scaleX = 1 / k, scaleY = 1 / k),
                                )
                            }
                        }
                    }
                }
                if (editing) Suggestions(address.text, settings.searchEngine, onPick = { navigate(tab ?: return@Suggestions, it) })
            }
            if (showMedia && !chromeHidden && tab != null && tab.media.isNotEmpty() && !tab.home) {
                Divider()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).background(c.panel)) {
                    items(tab.media.reversed(), key = { it.url }) { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                showMedia = false
                                startDownload(AddRequest(m.url, tab.headersFor(m.url, m.referer), fromBrowser = true))
                            }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.background(c.panel2, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Label(m.kind) }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                Uri.parse(m.url).lastPathSegment ?: m.url,
                                style = Type.small, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                        }
                        Divider()
                    }
                }
            }
            if (!chromeHidden && tab != null && !editing) {
                LoginBar(tab, web)
                BottomBar(
                    tab = tab,
                    tabCount = manager.count(tab.private),
                    canBack = canGoBack,
                    onBack = { back(tab) },
                    onForward = { if (tab.home && tab.web?.url != null) tab.home = false else tab.web?.goForward() },
                    onHome = { goHome(tab) },
                    onTabs = { tab.capture(); showTabs = true },
                    onMenu = { showMenu = true },
                )
            }
        }

        // ---------- over the page ----------
        if (BrowserChrome.videoMode && fullScreen == null && !BrowserChrome.pip) {
            ctx.findActivity()?.let { act ->
                VideoOverlay(web, act, onExit = {
                    web?.evaluateJavascript("window.__fdVideoMode && window.__fdVideoMode(false)", null)
                    BrowserChrome.videoMode = false
                })
            }
        }
        if (showTabs) {
            TabSwitcher(
                manager = manager,
                startPrivate = private,
                onPick = { manager.select(it); showTabs = false },
                onNew = { p -> newTab(p) },
                onClose = { showTabs = false },
            )
        }
        if (showMenu && tab != null) {
            MenuSheet(
                tab = tab,
                onDismiss = { showMenu = false },
                onNewTab = { showMenu = false; newTab(false) },
                onNewPrivate = { showMenu = false; newTab(true) },
                onLibrary = { showMenu = false; library = it },
                onDownloads = { showMenu = false; onDownloads() },
                onSettings = { showMenu = false; onSettings() },
                onFind = { showMenu = false; finding = true },
                onReader = {
                    showMenu = false
                    val w = tab.web ?: return@MenuSheet
                    Reader.extract(w) { doc ->
                        if (doc != null) reader = doc
                        else Toast.makeText(ctx, "No article found on this page", Toast.LENGTH_SHORT).show()
                    }
                },
                onDesktop = {
                    showMenu = false
                    tab.desktop = !tab.desktop
                    tab.web?.let { w ->
                        w.setDesktop(tab.desktop)
                        // Loaded afresh, not reloaded: a reload keeps the old zoom, so a desktop page wouldn't fit the screen.
                        w.url?.takeIf { it.startsWith("http") }
                            ?.let { w.loadUrl(if (tab.desktop) it else Omnibox.withoutDesktopSwitch(it)) } ?: w.reload()
                    }
                    manager.save()
                },
                onTranslate = {
                    showMenu = false
                    Omnibox.translateUrl(tab.url, Locale.getDefault())?.let { tab.load(it) }
                        ?: Toast.makeText(ctx, "This page is already translated", Toast.LENGTH_SHORT).show()
                },
                onPrint = {
                    showMenu = false
                    val act = ctx.findActivity()
                    if (act != null && web != null) printPage(act, web, tab.title.ifBlank { Omnibox.displayHost(tab.url) })
                },
                onClosePrivate = { showMenu = false; manager.closeAll(private = true) },
            )
        }
        if (showShields && tab != null) {
            ShieldsSheet(tab = tab, host = pageHost, onDismiss = { showShields = false }, onSettings = { showShields = false; onSettings() })
        }
        linkTarget?.let { (from, target) ->
            LinkSheet(
                target = target,
                onDismiss = { linkTarget = null },
                onOpen = { url, p, select ->
                    linkTarget = null
                    val t = manager.open(url, p, parent = if (p == from.private) from else null, select = select)
                    if (!select) Toast.makeText(ctx, "Opened in a background tab", Toast.LENGTH_SHORT).show()
                    if (!select) manager.webOf(t)
                },
                onDownload = { url ->
                    linkTarget = null
                    startDownload(AddRequest(url, from.headersFor(url, from.url), fromBrowser = true))
                },
            )
        }
        library?.let { page ->
            LibraryScreen(
                start = page,
                onClose = { library = null },
                onOpen = { url -> library = null; tab?.let { navigate(it, url) } },
                onOpenNewTab = { url -> library = null; manager.open(url, private) },
            )
        }
        reader?.let { doc -> ReaderView(doc, onClose = { reader = null }) }
        if (BrowserChrome.downloadsSheet) {
            DownloadsSheet(onDismiss = { BrowserChrome.downloadsSheet = false }, onAll = { BrowserChrome.downloadsSheet = false; onDownloads() })
        }
    }
}

/** Shares [text] (a page address) with another app. */
fun shareText(ctx: Context, text: String, title: String? = null) {
    runCatching {
        ctx.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).apply {
                if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
            }, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun LoadingLine(progress: Int) {
    val c = colors
    Box(Modifier.fillMaxWidth().height(2.dp).background(c.barBg)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(5, 100) / 100f)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(c.accent, c.accent2))),
        )
    }
}

/** The system print dialog for the page, where "Save as PDF" is one of the printers. */
fun printPage(act: Activity, web: WebView, title: String) {
    val pm = act.getSystemService(PrintManager::class.java) ?: return
    val name = title.replace(Regex("""[\/:*?"<>|]"""), " ").trim().take(80).ifEmpty { "Page" }
    pm.print(name, web.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
