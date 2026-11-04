package com.fastdownloader.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.fastdownloader.app.browser.BrowserStore
import com.fastdownloader.app.browser.Omnibox
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fastdownloader.app.BuildConfig
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Filters
import com.fastdownloader.app.engine.Net
import com.fastdownloader.app.engine.Format
import com.fastdownloader.app.engine.Settings
import com.fastdownloader.app.engine.Updater
import com.fastdownloader.app.engine.Ytdlp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The groups on the main Settings page; each opens its own page. */
private enum class SettingsPage(val title: String) {
    BROWSER("Browser"), PASSWORDS("Passwords"), SHIELDS("Shields & privacy"), VIDEO("Video"),
    DOWNLOADS("Downloads"), APPEARANCE("Appearance"), ABOUT("About"),
}

@Composable
fun SettingsScreen() {
    val c = colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Engine.settingsFlow.collectAsState()
    fun set(change: (Settings) -> Settings) = Engine.updateSettings(change)
    var ipResult by remember { mutableStateOf<String?>(null) }
    var ytdlpStatus by remember { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }

    val open = page
    if (open == null) {
        SettingsHome(s) { page = it }
        return
    }
    // Each page starts at its top.
    androidx.compose.runtime.key(open) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Back, "Back to Settings", size = 44.dp) { page = null }
            Text(open.title, style = Type.title, color = c.text)
        }
        Divider()
        when (open) {
        SettingsPage.BROWSER -> {
            Spacer(Modifier.height(12.dp))
            Card {
                    ChoiceList("Search engine", Omnibox.ENGINES.map { it.id to it.name }, s.searchEngine) { v -> set { it.copy(searchEngine = v) } }
                    TextSetting("Homepage (empty = start page)", s.homepageUrl) { v -> set { it.copy(homepageUrl = v.trim()) } }
                    Toggle("Reopen tabs on start", "The tabs you had open come back (private tabs never do)", s.restoreTabs) { v -> set { it.copy(restoreTabs = v) } }
                    Toggle("Desktop site for new tabs", "Ask sites for their computer layout", s.desktopMode) { v -> set { it.copy(desktopMode = v) } }
                    Toggle("Detect file links", "Links to zip, apk, pdf and other files open the download panel", s.detectFileLinks) { v -> set { it.copy(detectFileLinks = v) } }
                    Toggle("Quick download", "Plain files from pages start without pressing Download", s.quickDownload, last = true) { v -> set { it.copy(quickDownload = v) } }
            }
        }
        SettingsPage.PASSWORDS -> {
            Spacer(Modifier.height(12.dp))
            Card {
                PasswordSettings()
            }
        }
        SettingsPage.SHIELDS -> {
            Section("Shields") {
                    Toggle("Block trackers & ads", "uBlock Origin, EasyList and EasyPrivacy filter lists", s.blockAds) { v -> set { it.copy(blockAds = v) } }
                    Toggle("Upgrade connections to HTTPS", "Falls back to http:// only when a site has no secure version", s.httpsUpgrade) { v -> set { it.copy(httpsUpgrade = v) } }
                    ChoiceList("Cookies", listOf("all" to "All", "block3p" to "No 3rd-party", "none" to "None"), s.cookiePolicy) { v -> set { it.copy(cookiePolicy = v) } }
                    val exceptions = (s.adblockAllow + s.httpsOffSites + s.scriptBlockSites + s.thirdPartyCookieSites).distinct()
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Per-site Shields", style = Type.strong, color = c.text)
                            Text(if (exceptions.isEmpty()) "None. Change them with the shield button on a page." else exceptions.joinToString(", "), style = Type.small, color = c.muted)
                        }
                        if (exceptions.isNotEmpty()) LineButton("Reset") {
                            set { it.copy(adblockAllow = emptyList(), httpsOffSites = emptyList(), scriptBlockSites = emptyList(), thirdPartyCookieSites = emptyList()) }
                        }
                    }
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LineButton("Clear history", Modifier.weight(1f)) {
                            BrowserStore.clearHistory()
                            android.widget.Toast.makeText(ctx, "Browsing history cleared", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        LineButton("Clear cookies", Modifier.weight(1f)) {
                            com.fastdownloader.app.browser.SignIns.clearCookiesKeepingSignIns()
                            android.webkit.WebStorage.getInstance().deleteAllData()
                            android.widget.Toast.makeText(ctx, "Cookies cleared. You stay signed in to Google and YouTube.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
            }
            Section("Privacy") {
                    Choice("Proxy", listOf("none" to "None", "http" to "HTTP", "socks5" to "SOCKS5"), s.proxyType) { v -> set { it.copy(proxyType = v) } }
                    if (s.proxyType != "none") {
                        TextSetting("Proxy host", s.proxyHost) { v -> set { it.copy(proxyHost = v.trim()) } }
                        NumberField("Proxy port", s.proxyPort, null) { v -> set { it.copy(proxyPort = v) } }
                        TextSetting("Username (optional)", s.proxyUser) { v -> set { it.copy(proxyUser = v) } }
                        TextSetting("Password (optional)", s.proxyPass, secret = true) { v -> set { it.copy(proxyPass = v) } }
                        Text(
                            "Downloads, video sites and torrents all go through the proxy. If it stops working, downloads fail instead of using your real address.",
                            style = Type.small, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Divider()
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Address sites see", style = Type.strong, color = c.text)
                            Text(ipResult ?: "Tap Check", style = Type.small, color = c.muted)
                        }
                        LineButton("Check") {
                            ipResult = "Checking…"
                            scope.launch { ipResult = withContext(Dispatchers.IO) { runCatching { Net.visibleIp() }.getOrElse { "Couldn't check: ${it.message}" } } }
                        }
                    }
                    Divider()
                    Toggle("Ask sites not to track", "Sends Do Not Track and Global Privacy Control", s.privacyHeaders) { v -> set { it.copy(privacyHeaders = v) } }
                    Toggle("Remove tracking tags from links", "utm_source, fbclid, gclid and others", s.stripTracking) { v -> set { it.copy(stripTracking = v) } }
                    FilterLists(s.adblockAllow) { set { it.copy(adblockAllow = emptyList()) } }
            }
        }
        SettingsPage.VIDEO -> {
            Section("Player") {
                    ChoiceList(
                        "YouTube quality",
                        listOf("wifi" to "Best on Wi-Fi", "highest" to "Highest", "hd1080" to "1080p", "hd720" to "720p", "auto" to "Auto"),
                        s.youtubeQuality,
                    ) { v -> set { it.copy(youtubeQuality = v) } }
                    ChoiceList("Full-screen shape", VideoCtl.FITS, s.videoFit) { v -> set { it.copy(videoFit = v) } }
                    Toggle("Turn sideways for full screen", "Upright videos stay upright", s.autoLandscape) { v -> set { it.copy(autoLandscape = v) } }
                    Toggle("Picture-in-picture", "Leaving the app while a video plays keeps it playing in a small window", s.autoPip, last = true) { v -> set { it.copy(autoPip = v) } }
            }
            Section("Video downloads") {
                    Choice("Video format", listOf("mp4" to "MP4", "mkv" to "MKV"), s.videoContainer) { v -> set { it.copy(videoContainer = v) } }
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Video site support (yt-dlp)", style = Type.strong, color = c.text)
                            Text(ytdlpStatus ?: (Ytdlp.version(ctx)?.let { "Version $it · updates daily" } ?: "Updates daily"), style = Type.small, color = c.muted)
                        }
                        LineButton("Update") {
                            ytdlpStatus = "Updating…"
                            scope.launch {
                                ytdlpStatus = withContext(Dispatchers.IO) { runCatching { Ytdlp.updateIfDue(ctx, force = true) }.getOrElse { "Update failed: ${it.message}" } }
                            }
                        }
                    }
                    Divider()
                    Toggle("Hide YouTube Shorts", "In the Browser tab; Shorts links open as normal videos", s.hideShorts) { v -> set { it.copy(hideShorts = v) } }
            }
        }
        SettingsPage.DOWNLOADS -> {
            Section("Downloads") {
                    Info("Download folder", Engine.rootDir.path.substringAfter("/emulated/0/"))
                    Toggle("Sort into category folders", "ZIP, DOC, Programs, Videos, Music, Torrent, Images, Other", s.categorize) { v -> set { it.copy(categorize = v) } }
                    Stepper("Downloads at once", s.maxConcurrent, 1..10) { v -> set { it.copy(maxConcurrent = v) } }
                    Stepper("Parts per file", s.segments, 1..32, hint = "More parts = faster on most servers") { v -> set { it.copy(segments = v) } }
                    NumberField("Speed limit (KB/s)", s.speedLimitKBps, "0 = no limit") { v -> set { it.copy(speedLimitKBps = v) } }
                    Toggle("Wi-Fi only", "Wait instead of using mobile data", s.wifiOnly) { v -> set { it.copy(wifiOnly = v) } }
                    Toggle("Catch copied links", "Offer to download a link you copy", s.clipboardMonitor) { v -> set { it.copy(clipboardMonitor = v) } }
                    Toggle("Notify when done", null, s.notifyOnComplete, last = true) { v -> set { it.copy(notifyOnComplete = v) } }
            }
            Section("History") {
                    Toggle("Keep download history", "Off: finished downloads leave the list (files stay)", s.keepHistory) { v -> set { it.copy(keepHistory = v) } }
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LineButton("Clear download history") { Engine.clearHistory() }
                    }
            }
            Section("Torrents") {
                    Stepper("Max connections", s.torrentConnections, 20..1000, step = 10) { v -> set { it.copy(torrentConnections = v) } }
                    Toggle("Extra public trackers", "Finds more peers for magnet links", s.torrentExtraTrackers) { v -> set { it.copy(torrentExtraTrackers = v) } }
                    Toggle(
                        "Only over a VPN", "Kill switch: torrents pause whenever no VPN is connected" + if (Engine.onVpn) " · VPN on now" else " · no VPN now",
                        s.torrentVpnOnly, last = true,
                    ) { v -> set { it.copy(torrentVpnOnly = v) } }
            }
        }
        SettingsPage.APPEARANCE -> {
            Spacer(Modifier.height(12.dp))
            Card {
                    Choice("Theme", listOf("system" to "Auto", "light" to "Light", "dark" to "Dark"), s.themeMode) { v -> set { it.copy(themeMode = v) } }
                    Stepper("Page text size (%)", s.textZoom, 50..200, step = 10, hint = "Applies to tabs opened from now on") { v -> set { it.copy(textZoom = v) } }
                    Toggle("Shields totals on the start page", null, s.showHomeStats) { v -> set { it.copy(showHomeStats = v) } }
                    Toggle("Top sites on the start page", "Your most visited sites, from history", s.showTopSites, last = true) { v -> set { it.copy(showTopSites = v) } }
            }
        }
        SettingsPage.ABOUT -> {
            Section("About") {
                    Info("Ghostream", "Version ${BuildConfig.VERSION_NAME} for Android 8 to 17")
                    AppUpdate()
                    Toggle("Check for updates", "Asks GitHub every 6 hours and notifies you of a new version", s.checkUpdates) { v -> set { it.copy(checkUpdates = v) } }
                    Info("Made by", "Copyright © 2026 Janardan/TSE" + if (s.userName.isNotBlank()) " · registered to ${s.userName}" else "")
                    Info(
                        "Licence",
                        "Free software under the GNU GPL v3, with no warranty. Source code: github.com/Janardanpal007/Ghostream (android folder).",
                    )
                    LinkRow("LinkedIn", "linkedin.com/in/janardan-pal", "https://www.linkedin.com/in/janardan-pal")
                    LinkRow("Discord", "Message me on Discord", "https://discord.dog/704385778809307226")
                    LinkRow("GitHub", "github.com/Janardanpal007", "https://github.com/Janardanpal007", last = true)
            }
            Section("Profile") {
                    TextSetting("Your name", s.userName, last = true) { v -> set { it.copy(userName = v) } }
            }
        }
        }
        Spacer(Modifier.height(24.dp))
    } }
}

/** The main page: one row per group, each with what's set now, so most people never need to open them. */
@Composable
private fun SettingsHome(s: Settings, onOpen: (SettingsPage) -> Unit) {
    val c = colors
    val vault by com.fastdownloader.app.browser.Passwords.vault.collectAsState()
    val update by Updater.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(12.dp))
        Card {
            val engine = Omnibox.ENGINES.firstOrNull { it.id == s.searchEngine }?.name ?: s.searchEngine
            GroupRow(Icons.Globe, "Browser", engine + if (s.restoreTabs) " · reopens tabs" else "") { onOpen(SettingsPage.BROWSER) }
            GroupRow(Icons.Key, "Passwords", if (vault.logins.isEmpty()) "Fingerprint, import from Google" else "${vault.logins.size} saved · fingerprint") { onOpen(SettingsPage.PASSWORDS) }
            GroupRow(Icons.Shield, "Shields & privacy", if (s.blockAds) "Blocking ads & trackers" else "Ad blocking is off") { onOpen(SettingsPage.SHIELDS) }
            val quality = mapOf("wifi" to "best on Wi-Fi", "highest" to "highest", "hd1080" to "1080p", "hd720" to "720p", "auto" to "auto")[s.youtubeQuality] ?: s.youtubeQuality
            GroupRow(Icons.Video, "Video", "YouTube $quality · " + s.videoContainer.uppercase()) { onOpen(SettingsPage.VIDEO) }
            GroupRow(Icons.Folder, "Downloads", "${s.maxConcurrent} at once · ${s.segments} parts" + if (s.wifiOnly) " · Wi-Fi only" else "") { onOpen(SettingsPage.DOWNLOADS) }
            GroupRow(Icons.TextSize, "Appearance", mapOf("system" to "Auto theme", "light" to "Light theme", "dark" to "Dark theme")[s.themeMode] ?: s.themeMode) { onOpen(SettingsPage.APPEARANCE) }
            GroupRow(
                Icons.Info, "About",
                update.available?.let { "Version ${it.version} is available" } ?: "Version ${BuildConfig.VERSION_NAME} · updates, contact",
                highlight = update.available != null, last = true,
            ) { onOpen(SettingsPage.ABOUT) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Settings in a card, for a page that has one group (the page title names it). */
@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(Shape.card).border(1.dp, colors.border, Shape.card).background(colors.panel),
    ) { content() }
}

@Composable
private fun GroupRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, highlight: Boolean = false, last: Boolean = false, onClick: () -> Unit) {
    val c = colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(Shape.control).background(c.panel2), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, null, tint = c.accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong, color = c.text)
            Text(summary, style = Type.small, color = if (highlight) c.accent else c.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        androidx.compose.material3.Icon(Icons.Forward, null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
    if (!last) Divider()
}

/** uBlock Origin's lists: how many rules are loaded, when they were updated, and an Update button. */
@Composable
private fun FilterLists(allowedSites: List<String>, onClearAllowed: () -> Unit) {
    val c = colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Filters.status.collectAsState()
    LaunchedEffect(Unit) { if (Filters.engine == null) withContext(Dispatchers.IO) { runCatching { Filters.load(ctx) } } }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Filter lists", style = Type.strong, color = c.text)
        Text(Filters.LISTS.joinToString(", ") { it.name }, style = Type.small, color = c.muted)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                st.loading -> st.message ?: "Loading…"
                st.networkRules == 0 -> "Loads when you open the Browser tab"
                else -> "%,d blocking and %,d hiding rules".format(st.networkRules, st.cosmeticRules) +
                    (if (st.updatedAt > 0) " · updated " + java.text.DateFormat.getDateInstance().format(java.util.Date(st.updatedAt)) else " · built-in copy") +
                    (st.message?.let { " · $it" } ?: "")
            },
            style = Type.small, color = c.muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineButton("Update lists", enabled = !st.loading) {
                scope.launch { withContext(Dispatchers.IO) { runCatching { Filters.updateIfDue(ctx, force = true) } } }
            }
            if (allowedSites.isNotEmpty()) LineButton("Block on ${allowedSites.size} allowed site" + if (allowedSites.size == 1) "" else "s") { onClearAllowed() }
        }
    }
    Divider()
}

/** Newest version: check, download the APK (checked against its SHA-256), install. */
@Composable
private fun AppUpdate() {
    val c = colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    val r = st.available
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(if (r != null) "Version ${r.version} is available" else "Updates", style = Type.strong, color = c.text)
        Text(
            when {
                st.error != null -> st.error!!
                st.checking -> "Checking…"
                st.progress != null -> "Downloading… ${(st.progress!! * 100).toInt()}%"
                st.ready != null -> "Downloaded and checked. Tap Install; Android asks you to confirm."
                r != null && r.apkUrl == null -> "This release has no Android app yet."
                r != null -> "You have ${st.current}." + if (r.apkSize > 0) " Download: ${Format.size(r.apkSize)}" else ""
                st.lastCheck > 0 -> "You have the newest version."
                else -> "Tap Check to look for a newer version."
            },
            style = Type.small, color = if (st.error != null) c.err else c.muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                st.ready != null -> SolidButton("Install", onClick = { Updater.install(ctx) })
                r?.apkUrl != null -> SolidButton("Download update", onClick = {
                    if (st.progress == null) scope.launch { withContext(Dispatchers.IO) { Updater.download(ctx) } }
                })
                else -> LineButton("Check", enabled = !st.checking) {
                    scope.launch { withContext(Dispatchers.IO) { runCatching { Updater.check() } } }
                }
            }
            if (r != null) LineButton("What's new") { Updater.openPage(ctx) }
        }
    }
    Divider()
}

/** A way to reach the maker: opens in the LinkedIn, Discord or GitHub app when it's there, else in a browser. */
@Composable
private fun LinkRow(title: String, text: String, url: String, last: Boolean = false) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable {
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong, color = colors.text)
            Text(text, style = Type.small, color = colors.accent)
        }
        androidx.compose.material3.Icon(Icons.Open, "Open $title", tint = colors.muted, modifier = Modifier.padding(start = 12.dp))
    }
    if (!last) Divider()
}

@Composable
private fun Info(title: String, text: String, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = Type.strong, color = colors.text)
        Text(text, style = Type.small, color = colors.muted)
    }
    if (!last) Divider()
}

@Composable
private fun Toggle(title: String, hint: String?, value: Boolean, last: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = colors
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong, color = c.text)
            if (hint != null) Text(hint, style = Type.small, color = c.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.solidBg, checkedThumbColor = c.solidFg, uncheckedTrackColor = c.panel2,
                uncheckedBorderColor = c.lineStrong, uncheckedThumbColor = c.muted,
            ),
        )
    }
    if (!last) Divider()
}

@Composable
private fun Stepper(title: String, value: Int, range: IntRange, step: Int = 1, hint: String? = null, onChange: (Int) -> Unit) {
    val c = colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong, color = c.text)
            if (hint != null) Text(hint, style = Type.small, color = c.muted)
        }
        IconBox(Icons.Minus, "Less") { onChange((value - step).coerceIn(range)) }
        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { Text("$value", style = Type.heading, color = c.text) }
        IconBox(Icons.Add, "More") { onChange((value + step).coerceIn(range)) }
    }
    Divider()
}

@Composable
private fun Choice(title: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.strong, color = colors.text, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((key, label) in options) Chip(label, value == key) { onChange(key) }
        }
    }
    Divider()
}

/** A choice with many options: the options scroll sideways under the title. */
@Composable
private fun ChoiceList(title: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, style = Type.strong, color = colors.text, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((key, label) in options) Chip(label, value == key) { onChange(key) }
        }
    }
    Divider()
}

@Composable
private fun TextSetting(title: String, value: String, secret: Boolean = false, last: Boolean = false, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Label(title)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it; onChange(it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            textStyle = Type.body.copy(color = colors.text), colors = fieldColors(),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        )
    }
    if (!last) Divider()
}

@Composable
private fun NumberField(title: String, value: Int, hint: String?, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == 0 && hint == null) "" else value.toString()) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong, color = colors.text)
            if (hint != null) Text(hint, style = Type.small, color = colors.muted)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { t -> text = t.filter { it.isDigit() }.take(7); onChange(text.toIntOrNull() ?: 0) },
            modifier = Modifier.width(120.dp), singleLine = true, textStyle = Type.small.copy(color = colors.text), colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    Divider()
}
