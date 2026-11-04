package com.fastdownloader.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastdownloader.app.browser.BrowserStore
import com.fastdownloader.app.browser.BrowserTab
import com.fastdownloader.app.browser.LinkTarget
import com.fastdownloader.app.browser.Omnibox
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Format
import com.fastdownloader.app.engine.ItemView
import com.fastdownloader.app.engine.Settings
import com.fastdownloader.app.engine.Status
import java.text.DateFormat
import java.util.Date

/** A panel that slides up from the bottom over a dimmed page. */
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = colors
    BackHandler(onBack = onDismiss)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(shown, enter = slideInVertically { it / 2 } + fadeIn()) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 620.dp).clip(Shape.sheet).background(c.panel)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(bottom = 8.dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(Shape.pill).background(c.lineStrong))
                }
                content()
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, text: String, sub: String? = null, tint: Color = colors.text, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = Type.strong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
private fun Tile(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    val c = colors
    Column(
        modifier.clip(Shape.control).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(c.panel2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.text, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(text, style = Type.small, color = c.text, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
fun FdSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = colors
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.White, uncheckedTrackColor = c.panel2, uncheckedBorderColor = c.lineStrong),
    )
}

// ---------- menu ----------

@Composable
fun MenuSheet(
    tab: BrowserTab,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onNewPrivate: () -> Unit,
    onLibrary: (LibraryPage) -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    onFind: () -> Unit,
    onReader: () -> Unit,
    onDesktop: () -> Unit,
    onTranslate: () -> Unit,
    onPrint: () -> Unit,
    onClosePrivate: () -> Unit,
) {
    val ctx = LocalContext.current
    val c = colors
    val bookmarks by BrowserStore.bookmarks.collectAsState()
    val page = !tab.home && tab.url.startsWith("http")
    val bookmarked = page && bookmarks.any { it.url == tab.url }
    Sheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Tile(Icons.Add, "New tab", Modifier.weight(1f), onNewTab)
            Tile(Icons.Private, "Private", Modifier.weight(1f), onNewPrivate)
            Tile(Icons.Bookmark, "Bookmarks", Modifier.weight(1f)) { onLibrary(LibraryPage.BOOKMARKS) }
            Tile(Icons.History, "History", Modifier.weight(1f)) { onLibrary(LibraryPage.HISTORY) }
            Tile(Icons.Download, "Downloads", Modifier.weight(1f), onDownloads)
        }
        Divider()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (page) {
                SheetRow(if (bookmarked) Icons.Bookmark else Icons.BookmarkBorder, if (bookmarked) "Remove bookmark" else "Add bookmark", tint = if (bookmarked) c.accent else c.text) {
                    val added = BrowserStore.toggleBookmark(tab.url, tab.title)
                    Toast.makeText(ctx, if (added) "Bookmarked" else "Bookmark removed", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                ZoomRow(tab)
                SheetRow(Icons.Search, "Find in page", onClick = onFind)
                SheetRow(Icons.Article, "Reader mode", "Just the article, in clean text", onClick = onReader)
                SheetRow(Icons.Translate, "Translate page", "With Google Translate, into your phone's language", onClick = onTranslate)
                SheetRow(Icons.Print, "Print / Save as PDF", onClick = onPrint)
                SheetRow(
                    Icons.Desktop, "Desktop site",
                    trailing = { FdSwitch(tab.desktop) { onDesktop() } }, onClick = onDesktop,
                )
                SheetRow(Icons.Share, "Share") { shareText(ctx, tab.url, tab.title); onDismiss() }
                SheetRow(Icons.Copy, "Copy link") { copyText(ctx, tab.url); onDismiss() }
            }
            if (tab.private) SheetRow(Icons.Close, "Close all private tabs", tint = c.err, onClick = onClosePrivate)
            SheetRow(Icons.Tune, "Settings", onClick = onSettings)
        }
    }
}

private val ZOOMS = listOf(50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200)

/** Page zoom for this tab: smaller fits more of a page on the screen, bigger is easier to read. */
@Composable
private fun ZoomRow(tab: BrowserTab) {
    val c = colors
    fun step(dir: Int) {
        val i = ZOOMS.indexOfFirst { it >= tab.zoom }.let { if (it < 0) ZOOMS.lastIndex else it }
        tab.zoom = ZOOMS[(i + dir).coerceIn(0, ZOOMS.lastIndex)]
        tab.applyZoom()
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.ZoomIn, null, tint = c.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text("Zoom", style = Type.strong, color = c.text, modifier = Modifier.weight(1f))
        RoundIcon(Icons.Minus, "Zoom out", size = 40.dp, tint = c.text, enabled = tab.zoom > ZOOMS.first()) { step(-1) }
        Text(
            "${tab.zoom}%", style = Type.label, color = c.text, textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp).clickable { tab.zoom = 100; tab.applyZoom() },
        )
        RoundIcon(Icons.Add, "Zoom in", size = 40.dp, tint = c.text, enabled = tab.zoom < ZOOMS.last()) { step(1) }
    }
}

// ---------- Shields ----------

/** Per-site protections for the page's site, plus how much was blocked on this page. */
@Composable
fun ShieldsSheet(tab: BrowserTab, host: String, onDismiss: () -> Unit, onSettings: () -> Unit) {
    val c = colors
    val s by Engine.settingsFlow.collectAsState()
    fun has(list: List<String>) = host.isNotEmpty() && list.any { Omnibox.matchesSite(host, it) }
    fun toggled(list: List<String>, on: Boolean) = if (on) list + host else list.filterNot { Omnibox.matchesSite(host, it) }
    fun change(f: (Settings) -> Settings) {
        Engine.updateSettings(f)
        if (!tab.home) tab.web?.reload()
    }
    // On the start page (where new and private tabs open) the switch is for every site.
    val site = !tab.home && host.isNotEmpty()
    val adsOn = s.blockAds && !(site && has(s.adblockAllow))
    // Always works: turning it on also turns blocking back on everywhere if it was off in Settings.
    fun setAds(on: Boolean) = change {
        when {
            on && !it.blockAds -> it.copy(blockAds = true, adblockAllow = if (site) toggled(it.adblockAllow, false) else it.adblockAllow)
            site -> it.copy(adblockAllow = toggled(it.adblockAllow, !on))
            else -> it.copy(blockAds = on)
        }
    }
    Sheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(if (adsOn) c.ok.copy(alpha = 0.15f) else c.panel2),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Shield, null, tint = if (adsOn) c.ok else c.muted, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.height(8.dp))
            Text(
                if (site) host else if (tab.private) "Shields · private tab" else "Shields",
                style = Type.heading, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    !s.blockAds -> "Shields are off everywhere"
                    !site -> "Shields are UP on every site"
                    adsOn -> "Shields are UP · ${tab.blocked} blocked on this page"
                    else -> "Shields are DOWN for this site"
                },
                style = Type.small, color = if (adsOn) c.ok else c.muted,
            )
            if (site) Text(
                if (tab.secure) "Connection is secure (HTTPS)" else "Connection is not secure",
                style = Type.small, color = if (tab.secure) c.muted else c.warn, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Divider()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetRow(
                Icons.Shield, if (site) "Block trackers & ads" else "Block trackers & ads on every site",
                "uBlock Origin, EasyList and EasyPrivacy lists",
                trailing = { FdSwitch(adsOn) { on -> setAds(on) } },
            ) { setAds(!adsOn) }
            if (site) {
                val https = s.httpsUpgrade && !has(s.httpsOffSites)
                SheetRow(
                    Icons.Lock, "Upgrade connections to HTTPS", if (s.httpsUpgrade) null else "Off everywhere in Settings",
                    trailing = { FdSwitch(https) { on -> if (s.httpsUpgrade) change { it.copy(httpsOffSites = toggled(it.httpsOffSites, !on)) } } },
                ) { if (s.httpsUpgrade) change { it.copy(httpsOffSites = toggled(it.httpsOffSites, https)) } }
                val scripts = has(s.scriptBlockSites)
                SheetRow(
                    Icons.Code, "Block scripts", "Pages may not work without them",
                    trailing = { FdSwitch(scripts) { on -> change { it.copy(scriptBlockSites = toggled(it.scriptBlockSites, on)) } } },
                ) { change { it.copy(scriptBlockSites = toggled(it.scriptBlockSites, !scripts)) } }
                if (s.cookiePolicy == "block3p") {
                    val allow3p = has(s.thirdPartyCookieSites)
                    SheetRow(
                        Icons.Globe, "Allow third-party cookies", "Some sign-ins and embedded players need them",
                        trailing = { FdSwitch(allow3p) { on -> change { it.copy(thirdPartyCookieSites = toggled(it.thirdPartyCookieSites, on)) } } },
                    ) { change { it.copy(thirdPartyCookieSites = toggled(it.thirdPartyCookieSites, !allow3p)) } }
                } else {
                    SheetRow(Icons.Globe, "Cookies", if (s.cookiePolicy == "none") "All cookies blocked (Settings)" else "All cookies allowed (Settings)") { onSettings() }
                }
            }
            SheetRow(Icons.Tune, "Global Shields settings", onClick = onSettings)
        }
    }
}

// ---------- long press on a link or picture ----------

@Composable
fun LinkSheet(target: LinkTarget, onDismiss: () -> Unit, onOpen: (url: String, private: Boolean, select: Boolean) -> Unit, onDownload: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = colors
    val link = target.link?.takeIf { it.startsWith("http") }
    val image = target.image?.takeIf { it.startsWith("http") }
    Sheet(onDismiss) {
        Text(
            link ?: image ?: target.link ?: target.image ?: "",
            style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Divider()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (link != null) {
                SheetRow(Icons.Add, "Open in new tab") { onOpen(link, false, true) }
                SheetRow(Icons.Globe, "Open in background tab") { onOpen(link, false, false) }
                SheetRow(Icons.Private, "Open in private tab") { onOpen(link, true, true) }
                SheetRow(Icons.Download, "Download link", tint = c.accent) { onDownload(link) }
                SheetRow(Icons.Copy, "Copy link") { copyText(ctx, link); onDismiss() }
                SheetRow(Icons.Share, "Share link") { shareText(ctx, link); onDismiss() }
            }
            if (image != null) {
                if (link != null) Divider()
                SheetRow(Icons.Image, "Open image in new tab") { onOpen(image, false, true) }
                SheetRow(Icons.Download, "Download image", tint = c.accent) { onDownload(image) }
                SheetRow(Icons.Copy, "Copy image address") { copyText(ctx, image); onDismiss() }
            }
            if (link == null && image == null) {
                Text("This link can't be opened here.", style = Type.body, color = c.muted, modifier = Modifier.padding(20.dp))
            }
        }
    }
}

// ---------- download progress ----------

/** The downloads at a glance, over the page: progress, and pause, resume, retry and cancel. */
@Composable
fun DownloadsSheet(onDismiss: () -> Unit, onAll: () -> Unit) {
    val c = colors
    val items by Engine.itemsFlow.collectAsState()
    val active = items.filter { it.status != Status.COMPLETED }
    val done = items.filter { it.status == Status.COMPLETED }.sortedByDescending { it.finishedAt ?: 0 }.take(3)
    Sheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", style = Type.heading, color = c.text)
                val running = items.count { it.status == Status.DOWNLOADING }
                Text(
                    if (running > 0) "$running running · ${Format.speed(items.sumOf { it.speed })}" else "${active.size} waiting or paused",
                    style = Type.small, color = c.muted,
                )
            }
            LineButton("All downloads", onClick = onAll)
        }
        Divider()
        if (active.isEmpty() && done.isEmpty()) {
            Text("Nothing downloaded yet. Tap a video's download button, hold a link, or open a file link.", style = Type.body, color = c.muted, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(active + done, key = { it.id }) { item ->
                DownloadRow(item)
            }
        }
    }
}

@Composable
private fun DownloadRow(item: ItemView) {
    val c = colors
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.fileName, style = Type.strong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = when (item.status) {
                    Status.DOWNLOADING -> listOfNotNull(
                        if (item.total > 0) "${Format.size(item.received)} / ${Format.size(item.total)}" else Format.size(item.received),
                        Format.speed(item.speed), Format.eta(item.etaSeconds).ifEmpty { null },
                    ).joinToString(" · ")
                    Status.QUEUED -> item.note ?: "Waiting in queue"
                    Status.PAUSED -> "Paused · ${Format.size(item.received)}" + if (item.total > 0) " / ${Format.size(item.total)}" else ""
                    Status.ERROR -> item.error ?: "Failed"
                    Status.COMPLETED -> "Done · ${Format.size(item.total)} · ${item.category}"
                }
                Text(line, style = Type.small, color = if (item.status == Status.ERROR) c.err else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (item.status) {
                Status.DOWNLOADING, Status.QUEUED -> RoundIcon(Icons.Pause, "Pause") { Engine.pause(item.id) }
                Status.PAUSED -> RoundIcon(Icons.Play, "Resume", tint = c.accent) { Engine.resume(item.id) }
                Status.ERROR -> RoundIcon(Icons.Refresh, "Retry", tint = c.accent) { Engine.resume(item.id) }
                Status.COMPLETED -> RoundIcon(Icons.Open, "Open") {
                    if (!openFile(ctx, item.path)) Toast.makeText(ctx, "The file isn't there any more", Toast.LENGTH_SHORT).show()
                }
            }
            if (item.status != Status.COMPLETED) RoundIcon(Icons.Close, "Cancel", tint = c.muted) { Engine.remove(item.id, deleteFile = true) }
        }
        if (item.status != Status.COMPLETED) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(Shape.pill).background(c.barBg)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(item.fraction.coerceIn(0.02f, 1f)).clip(Shape.pill)
                        .background(if (item.status == Status.ERROR) c.err else if (item.status == Status.PAUSED) c.muted else c.accent),
                )
            }
        }
    }
}

// ---------- bookmarks and history ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(start: LibraryPage, onClose: () -> Unit, onOpen: (String) -> Unit, onOpenNewTab: (String) -> Unit) {
    val c = colors
    val ctx = LocalContext.current
    var page by remember { mutableStateOf(start) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    val bookmarks by BrowserStore.bookmarks.collectAsState()
    val history by BrowserStore.history.collectAsState()
    OverlayPage(
        title = if (page == LibraryPage.BOOKMARKS) "Bookmarks" else "History",
        onClose = onClose,
        actions = {
            if (page == LibraryPage.HISTORY && history.isNotEmpty()) LineButton("Clear all", color = c.err) { BrowserStore.clearHistory() }
            if (page == LibraryPage.BOOKMARKS && bookmarks.isNotEmpty()) LineButton("Share") { shareBookmarks(ctx) }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Bookmarks ${bookmarks.size}", page == LibraryPage.BOOKMARKS) { page = LibraryPage.BOOKMARKS }
                Chip("History ${history.size}", page == LibraryPage.HISTORY) { page = LibraryPage.HISTORY }
            }
            Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(42.dp).clip(Shape.pill).background(c.panel2).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(query, { query = it }, singleLine = true, textStyle = Type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                if (query.isEmpty()) Text("Search", style = Type.body, color = c.muted)
            }
            Spacer(Modifier.height(6.dp))
            val q = query.trim().lowercase()
            val rows: List<Triple<String, String, Long>> = if (page == LibraryPage.BOOKMARKS) {
                bookmarks.map { Triple(it.title, it.url, it.addedAt) }
            } else {
                history.map { Triple(it.title, it.url, it.at) }
            }.filter { q.isEmpty() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
            if (rows.isEmpty()) {
                Text(
                    if (page == LibraryPage.BOOKMARKS) "No bookmarks yet. Open the menu on a page and tap Add bookmark." else "No history.",
                    style = Type.body, color = c.muted, modifier = Modifier.padding(20.dp),
                )
            }
            val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.second + it.third }) { (title, url, at) ->
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(url) }, onLongClick = { onOpenNewTab(url) })
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SiteAvatar(url, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            if (editing == url) {
                                var text by remember { mutableStateOf(title) }
                                BasicTextField(
                                    text, { text = it }, singleLine = true, textStyle = Type.strong.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                                    modifier = Modifier.fillMaxWidth().clip(Shape.control).background(c.panel2).padding(8.dp),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { BrowserStore.renameBookmark(url, text); editing = null }),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                                )
                            } else {
                                Text(title, style = Type.strong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                if (page == LibraryPage.HISTORY) "${Omnibox.displayHost(url)} · ${fmt.format(Date(at))}" else url,
                                style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (page == LibraryPage.BOOKMARKS) RoundIcon(Icons.Edit, "Rename", tint = c.muted) { editing = if (editing == url) null else url }
                        RoundIcon(Icons.Close, "Remove", tint = c.muted) {
                            if (page == LibraryPage.BOOKMARKS) BrowserStore.removeBookmark(url) else BrowserStore.removeVisit(url)
                        }
                    }
                }
            }
        }
    }
}
