package com.fastdownloader.app.ui

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastdownloader.app.R
import com.fastdownloader.app.browser.BrowserStore
import com.fastdownloader.app.browser.BrowserTab
import com.fastdownloader.app.browser.Omnibox
import com.fastdownloader.app.browser.PrivateProfile
import com.fastdownloader.app.browser.TabManager
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Status

// ---------- address bar ----------

@Composable
fun AddressBar(
    tab: BrowserTab,
    editing: Boolean,
    address: TextFieldValue,
    focusRequester: FocusRequester,
    shieldsOn: Boolean,
    onAddress: (TextFieldValue) -> Unit,
    onFocus: (Boolean) -> Unit,
    onGo: () -> Unit,
    onReload: () -> Unit,
    onShields: () -> Unit,
    downloads: @Composable () -> Unit,
) {
    val c = colors
    val bar = if (tab.private) c.privateBg else c.panel
    val field = if (tab.private) Color.White.copy(alpha = 0.08f) else c.panel2
    val textColor = if (tab.private) c.privateFg else c.text
    val shown = when {
        editing -> address
        tab.home -> TextFieldValue("")
        else -> TextFieldValue(Omnibox.searchTerms(tab.url) ?: Omnibox.displayHost(tab.url))
    }
    Row(Modifier.fillMaxWidth().background(bar).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(42.dp).clip(Shape.pill).background(field).padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (icon, tint, label) = when {
                tab.private -> Triple(Icons.Private, c.accent2, "Private tab")
                tab.home -> Triple(Icons.Search, c.muted, "Search")
                tab.secure -> Triple(Icons.Lock, c.ok, "Secure connection")
                else -> Triple(Icons.Info, c.warn, "Not secure")
            }
            Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onShields), contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = tint, modifier = Modifier.size(16.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = shown,
                    onValueChange = { if (editing) onAddress(it) },
                    singleLine = true,
                    textStyle = Type.body.copy(color = textColor, fontSize = 15.sp),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { onGo() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { onFocus(it.isFocused) },
                )
                if (shown.text.isEmpty()) {
                    Text("Search or type an address", style = Type.body.copy(fontSize = 15.sp), color = c.muted, maxLines = 1)
                }
            }
            when {
                editing && address.text.isNotEmpty() -> RoundIcon(Icons.Close, "Clear", size = 34.dp) { onAddress(TextFieldValue("")) }
                !tab.home -> RoundIcon(if (tab.loading) Icons.Close else Icons.Refresh, if (tab.loading) "Stop" else "Reload", size = 34.dp, tint = textColor, onClick = onReload)
            }
        }
        // Shields stay one tap away, even while typing (new and private tabs open that way).
        Spacer(Modifier.width(6.dp))
        ShieldButton(on = shieldsOn, count = if (tab.home) 0 else tab.blocked, onClick = onShields)
        if (!editing) {
            Spacer(Modifier.width(6.dp))
            downloads()
        }
    }
}

@Composable
fun RoundIcon(icon: ImageVector, description: String, size: Dp = 40.dp, tint: Color = colors.text, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(20.dp)) }
}

/** Shields: on (green with the number blocked on this page) or down for the site. */
@Composable
private fun ShieldButton(on: Boolean, count: Int, onClick: () -> Unit) {
    val c = colors
    Box {
        Box(
            Modifier.size(40.dp).clip(Shape.control).background(if (on) c.ok.copy(alpha = 0.14f) else c.panel2).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Shield, "Shields ($count blocked)", tint = if (on) c.ok else c.muted, modifier = Modifier.size(19.dp)) }
        if (on && count > 0) Badge(if (count > 99) "99+" else "$count", c.ok, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Badge(text: String, bg: Color, modifier: Modifier) {
    Text(
        text, style = Type.label.copy(fontSize = 9.sp, letterSpacing = 0.sp), color = Color.White,
        modifier = modifier.clip(Shape.pill).background(bg).padding(horizontal = 4.dp, vertical = 0.dp),
    )
}

/**
 * The Download button. [videos] > 0: the number of videos on the page, in blue. Otherwise the number of downloads
 * in progress. [found]: something on the page can be downloaded.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsButton(onClick: () -> Unit, onLongClick: () -> Unit = onClick, videos: Int = 0, found: Boolean = false, modifier: Modifier = Modifier) {
    val c = colors
    val items by Engine.itemsFlow.collectAsState()
    val active = items.count { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }
    val badge = if (videos > 0) videos else active
    Box(modifier) {
        Box(
            Modifier.size(40.dp).clip(Shape.control).background(if (found) c.accentSoft else c.panel2)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Download,
                if (videos > 0) "Download ($videos videos on this page)" else "Downloads ($active in progress)",
                tint = if (found) c.accent else c.text, modifier = Modifier.size(19.dp),
            )
        }
        if (badge > 0) Badge(if (badge > 99) "99+" else "$badge", if (videos > 0) c.accent else c.accent2, Modifier.align(Alignment.TopEnd))
    }
}

// ---------- bottom bar ----------

@Composable
fun BottomBar(
    tab: BrowserTab,
    tabCount: Int,
    canBack: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
) {
    val c = colors
    val bar = if (tab.private) c.privateBg else c.panel
    val tint = if (tab.private) c.privateFg else c.text
    Column(Modifier.fillMaxWidth().background(bar)) {
        if (!tab.private) Divider()
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Back, "Back", size = 42.dp, tint = tint, enabled = canBack, onClick = onBack)
            RoundIcon(Icons.Forward, "Forward", size = 42.dp, tint = tint, enabled = tab.canForward || (tab.home && tab.web?.url != null), onClick = onForward)
            RoundIcon(Icons.Home, "Home", size = 42.dp, tint = tint, onClick = onHome)
            Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onTabs), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(22.dp).border(2.dp, tint, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (tabCount > 99) "∞" else "$tabCount", style = Type.label.copy(fontSize = 10.sp, letterSpacing = 0.sp), color = tint)
                }
            }
            RoundIcon(Icons.More, "Menu", size = 42.dp, tint = tint, onClick = onMenu)
        }
    }
}

// ---------- suggestions while typing ----------

@Composable
fun Suggestions(text: String, engineId: String, onPick: (String) -> Unit) {
    val c = colors
    val bookmarks by BrowserStore.bookmarks.collectAsState()
    val history by BrowserStore.history.collectAsState()
    val list = remember(text, bookmarks, history) { BrowserStore.suggest(text, 7) }
    val engine = Omnibox.engine(engineId)
    Column(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(Modifier.fillMaxWidth()) {
            if (text.isNotBlank()) {
                item {
                    SuggestionRow(Icons.Search, "Search “${text.trim()}”", "with ${engine.name}") { onPick(Omnibox.search(text.trim(), engineId)) }
                }
                Omnibox.resolve(text, engineId)?.takeIf { !it.startsWith(engine.query.substringBefore("%s")) }?.let { url ->
                    item { SuggestionRow(Icons.Globe, Omnibox.displayHost(url), url) { onPick(url) } }
                }
            }
            items(list, key = { "s" + it.url }) { v ->
                val bookmarked = bookmarks.any { it.url == v.url }
                SuggestionRow(if (bookmarked) Icons.Bookmark else Icons.History, v.title, v.url) { onPick(v.url) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val c = colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(c.panel2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.muted, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- find in page ----------

@Composable
fun FindBar(tab: BrowserTab, web: WebView, onClose: () -> Unit) {
    val c = colors
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(40.dp).clip(Shape.pill).background(c.panel2).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = q,
                onValueChange = { q = it; web.findAllAsync(it) },
                singleLine = true,
                textStyle = Type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { web.findNext(true) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (q.isEmpty()) Text("Find in page", style = Type.body, color = c.muted)
        }
        Text(
            if (q.isEmpty()) "" else "${tab.findIndex}/${tab.findCount}",
            style = Type.small, color = c.muted, modifier = Modifier.padding(horizontal = 8.dp),
        )
        RoundIcon(Icons.Up, "Previous", enabled = tab.findCount > 0) { web.findNext(false) }
        RoundIcon(Icons.Down, "Next", enabled = tab.findCount > 0) { web.findNext(true) }
        RoundIcon(Icons.Close, "Close find", onClick = onClose)
    }
    Divider()
}

// ---------- start page ----------

/** The new tab page: search, the totals Shields has blocked, most visited sites and bookmarks. */
@Composable
fun HomePage(private: Boolean, onOpen: (String) -> Unit, onSearch: () -> Unit) {
    val c = colors
    val settings by Engine.settingsFlow.collectAsState()
    val stats by BrowserStore.stats.collectAsState()
    val bookmarks by BrowserStore.bookmarks.collectAsState()
    val history by BrowserStore.history.collectAsState()
    val top = remember(history) { BrowserStore.topSites(8) }
    val enter = remember { MutableTransitionState(false).apply { targetState = true } }
    val bg = if (private) {
        Brush.verticalGradient(listOf(Color(0xFF241B45), Color(0xFF120E22)))
    } else {
        Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.20f), c.accent2.copy(alpha = 0.10f), c.bg))
    }
    val fg = if (private) c.privateFg else c.text
    val sub = if (private) c.privateFg.copy(alpha = 0.7f) else c.muted

    Box(Modifier.fillMaxSize().background(if (private) c.privateBg else c.bg).background(bg)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            AnimatedVisibility(enter, enter = fadeIn() + scaleIn(initialScale = 0.9f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (private) {
                        Box(
                            Modifier.size(64.dp).clip(Shape.card).background(Brush.linearGradient(listOf(c.accent, c.accent2))),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Private, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                    } else {
                        // The ghost, as on the launcher icon and the website.
                        Image(
                            painterResource(R.drawable.ic_launcher_foreground), "Ghostream",
                            Modifier.size(64.dp).clip(Shape.card).background(colorResource(R.color.icon_background)),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(if (private) "Private tab" else "Ghostream", style = Type.title, color = fg)
                    Text(
                        if (private) "Nothing from this tab is kept in history" else "Browser · Shields · Downloads",
                        style = Type.small, color = sub,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().height(52.dp).clip(Shape.pill)
                    .background(if (private) Color.White.copy(alpha = 0.08f) else c.panel)
                    .border(1.dp, if (private) Color.White.copy(alpha = 0.12f) else c.border, Shape.pill)
                    .clickable(onClick = onSearch).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Search, null, tint = sub, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Search with ${Omnibox.engine(settings.searchEngine).name}", style = Type.body, color = sub)
            }

            if (private) {
                Spacer(Modifier.height(24.dp))
                PrivateInfo()
            } else {
                if (settings.showHomeStats) {
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Trackers & ads blocked", "%,d".format(stats.blocked), c.ok, Modifier.weight(1f))
                        Stat("Upgraded to HTTPS", "%,d".format(stats.httpsUpgrades), c.accent, Modifier.weight(1f))
                        // About 50 ms of loading saved per blocked request: an estimate, labelled as one.
                        Stat("Time saved (est.)", duration(stats.blocked * 50 / 1000), c.accent2, Modifier.weight(1f))
                    }
                }
                if (settings.showTopSites && top.isNotEmpty()) {
                    Spacer(Modifier.height(22.dp))
                    SiteGrid("Top sites", top.map { it.title to it.url }, onOpen)
                }
                if (bookmarks.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    SiteGrid("Bookmarks", bookmarks.take(8).map { it.title to it.url }, onOpen)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun duration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

@Composable
private fun PrivateInfo() {
    val c = colors
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(Shape.card).background(Color.White.copy(alpha = 0.06f)).padding(18.dp),
    ) {
        Text("How private tabs work", style = Type.strong, color = c.privateFg)
        Spacer(Modifier.height(6.dp))
        Text(
            "Pages you open here aren't added to history or saved tabs. " + if (PrivateProfile.supported) {
                "Cookies, site data and cache are kept apart from normal tabs and erased when you close the last private tab."
            } else {
                "This phone's Android System WebView is too old to keep private cookies apart from normal tabs: update it from the Play Store for full isolation."
            } + " Your internet provider and the sites you visit can still see your activity.",
            style = Type.body, color = c.privateFg.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun Stat(label: String, value: String, tint: Color, modifier: Modifier) {
    val c = colors
    Column(modifier.clip(Shape.card).background(c.panel).border(1.dp, c.border, Shape.card).padding(12.dp)) {
        Text(value, style = Type.heading.copy(fontSize = 20.sp), color = tint, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = Type.small.copy(fontSize = 10.sp), color = c.muted, maxLines = 2)
    }
}

@Composable
private fun SiteGrid(title: String, sites: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    val c = colors
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Label(title, Modifier.padding(start = 4.dp, bottom = 10.dp))
        sites.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (name, url) ->
                    Column(
                        Modifier.weight(1f).clip(Shape.control).clickable { onOpen(url) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SiteAvatar(url, 48.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            name.ifBlank { Omnibox.displayHost(url) }, style = Type.small, color = c.text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A site's first letter on a colour made from its name (no icon is fetched, so nothing leaks to the site). */
@Composable
fun SiteAvatar(url: String, size: Dp) {
    val host = Omnibox.displayHost(url)
    val hues = listOf(0xFF1D7FE6, 0xFF7C5CFF, 0xFF138A43, 0xFFE0592A, 0xFFC2185B, 0xFF00838F, 0xFF6D4C41, 0xFF5E35B1)
    val color = Color(hues[(host.hashCode() and 0x7fffffff) % hues.size])
    Box(Modifier.size(size).clip(Shape.control).background(color), contentAlignment = Alignment.Center) {
        Text(host.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•", style = Type.heading, color = Color.White)
    }
}

// ---------- tab grid ----------

@Composable
fun TabSwitcher(manager: TabManager, startPrivate: Boolean, onPick: (BrowserTab) -> Unit, onNew: (Boolean) -> Unit, onClose: () -> Unit) {
    val c = colors
    var private by remember { mutableStateOf(startPrivate) }
    androidx.activity.compose.BackHandler(onBack = onClose)
    val shown = manager.tabs.filter { it.private == private }
    Column(Modifier.fillMaxSize().background(if (private) c.privateBg else c.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(Shape.pill).background(if (private) Color.White.copy(alpha = 0.08f) else c.panel2).padding(4.dp)) {
                Segment("Tabs · ${manager.count(false)}", !private, Modifier.weight(1f)) { private = false }
                Segment("Private · ${manager.count(true)}", private, Modifier.weight(1f)) { private = true }
            }
            Spacer(Modifier.width(8.dp))
            RoundIcon(Icons.Check, "Done", tint = if (private) c.privateFg else c.text, onClick = onClose)
        }
        if (shown.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (private) Icons.Private else Icons.Globe, null, tint = c.muted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (private) "No private tabs" else "No tabs", style = Type.strong, color = if (private) c.privateFg else c.text)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.id }) { t -> TabCard(t, selected = t.id == manager.currentId, onClick = { onPick(t) }, onClose = { manager.close(t) }) }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(if (private) c.privateBg else c.panel).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shown.isNotEmpty()) LineButton("Close all", color = if (private) c.privateFg else c.text) { manager.closeAll(private); if (private) onClose() }
            Spacer(Modifier.weight(1f))
            SolidButton(if (private) "New private tab" else "New tab", icon = Icons.Add) { onNew(private) }
        }
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = colors
    Box(
        modifier.height(36.dp).clip(Shape.pill).background(if (selected) c.solidBg else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), style = Type.label, color = if (selected) c.solidFg else c.muted, maxLines = 1) }
}

@Composable
private fun TabCard(t: BrowserTab, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val c = colors
    val border = if (selected) c.accent else if (t.private) Color.White.copy(alpha = 0.12f) else c.border
    Column(
        Modifier.fillMaxWidth().clip(Shape.card).border(if (selected) 2.dp else 1.dp, border, Shape.card)
            .background(if (t.private) Color.White.copy(alpha = 0.05f) else c.panel).clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = t.favicon
            if (icon != null) Image(icon, null, Modifier.size(16.dp).clip(CircleShape)) else if (!t.home && t.url.isNotEmpty()) SiteAvatar(t.url, 16.dp)
            else Icon(Icons.Home, null, tint = c.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (t.home) "New tab" else t.title.ifBlank { Omnibox.displayHost(t.url) },
                style = Type.small.copy(fontWeight = FontWeight.Bold), color = if (t.private) c.privateFg else c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            RoundIcon(Icons.Close, "Close tab", size = 32.dp, tint = c.muted, onClick = onClose)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).padding(6.dp).clip(Shape.control).background(if (t.private) Color.Black.copy(alpha = 0.3f) else c.panel2)) {
            val thumb = t.thumbnail
            if (thumb != null && !t.home) {
                Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (t.home || t.url.isEmpty()) Icon(Icons.Search, null, tint = c.muted, modifier = Modifier.size(28.dp))
                    else SiteAvatar(t.url, 44.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(if (t.home) "" else Omnibox.displayHost(t.url), style = Type.small, color = c.muted, maxLines = 1)
                }
            }
        }
    }
}

/** Full-height page with a title row, used by the library and reader. */
@Composable
fun OverlayPage(title: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val c = colors
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Back, "Close", onClick = onClose)
            Spacer(Modifier.width(6.dp))
            Text(title, style = Type.heading, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        Divider()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
