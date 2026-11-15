package com.fastdownloader.app.browser

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.fastdownloader.app.engine.Engine

/**
 * The open tabs. Normal tabs are saved as they change and come back on the next start; private tabs live
 * only in memory and their profile is wiped when the last one closes.
 */
class TabManager(private val ctx: Context, private val host: TabHost) {
    val tabs = mutableStateListOf<BrowserTab>()
    var currentId by mutableLongStateOf(-1L)
        private set
    private var nextId = 1L

    val current: BrowserTab? get() = tabs.firstOrNull { it.id == currentId }
    fun count(private: Boolean) = tabs.count { it.private == private }

    /** Reopens the last session's tabs (or one start page). */
    fun restore() {
        if (tabs.isNotEmpty()) return
        val session = BrowserStore.session
        if (Engine.settings.restoreTabs && (session.tabs.isNotEmpty() || session.privateTabs.isNotEmpty())) {
            session.tabs.forEach { t -> tabs += BrowserTab(nextId++, false, t.url, t.title, t.desktop).apply { olderBack = t.back } }
            val normalCurrent = tabs.getOrNull(session.current.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
            val privates = session.privateTabs.map { t -> BrowserTab(nextId++, true, t.url, t.title, t.desktop).apply { olderBack = t.back } }
            tabs += privates
            currentId = (privates.getOrNull(session.currentPrivate) ?: normalCurrent ?: tabs.first()).id
            if (tabs.none { !it.private }) open(startUrl(), private = false, select = false)
        } else {
            open(startUrl(), private = false)
        }
        // Private cookies and site data stay while private tabs are open; otherwise it's a profile left over from a crash.
        if (tabs.none { it.private }) PrivateProfile.wipe()
    }

    /** What new tabs open: the user's homepage, or the start page (""). */
    fun startUrl(): String = Engine.settings.homepageUrl.trim()

    fun open(url: String, private: Boolean, parent: BrowserTab? = null, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(nextId++, private, url, "", desktop = Engine.settings.desktopMode)
        tab.parentId = parent?.id
        val at = parent?.let { p -> tabs.indexOf(p) + 1 + tabs.drop(tabs.indexOf(p) + 1).takeWhile { it.parentId == p.id }.size } ?: tabs.size
        tabs.add(at.coerceIn(0, tabs.size), tab)
        if (select) select(tab)
        save()
        return tab
    }

    /** A tab for a window the page in [parent] opened, with its WebView made but nothing loaded. */
    fun openWindow(parent: BrowserTab): Pair<BrowserTab, WebView> {
        val tab = open("", parent.private, parent)
        return tab to webOf(tab)
    }

    fun select(tab: BrowserTab) {
        current?.takeIf { it !== tab }?.capture()
        currentId = tab.id
        tab.lastUsed = System.currentTimeMillis()
        trim()
        save()
    }

    /** The tab's WebView, made (and its page loaded) the first time it's needed. */
    fun webOf(tab: BrowserTab): WebView {
        tab.web?.let { return it }
        val w = tab.createWebView(ctx, host)
        if (tab.url.isNotEmpty()) tab.load(tab.url)
        return w
    }

    fun close(tab: BrowserTab) {
        val i = tabs.indexOf(tab)
        if (i < 0) return
        tabs.removeAt(i)
        tab.destroyWeb()
        if (tab.private && tabs.none { it.private }) PrivateProfile.wipe()
        if (currentId == tab.id) {
            // Back to the tab it was opened from, else a neighbour of the same kind.
            val next = tabs.firstOrNull { it.id == tab.parentId }
                ?: tabs.filter { it.private == tab.private }.let { same -> same.getOrNull(i.coerceAtMost(same.size - 1)) ?: same.lastOrNull() }
                ?: tabs.lastOrNull()
            if (next != null) select(next) else open(startUrl(), private = false)
        }
        save()
    }

    fun closeAll(private: Boolean) {
        tabs.filter { it.private == private }.forEach { it.destroyWeb() }
        tabs.removeAll { it.private == private }
        if (private) PrivateProfile.wipe()
        if (current == null) {
            val next = tabs.lastOrNull()
            if (next != null) select(next) else open(startUrl(), private = false)
        }
        save()
    }

    /** At most a few WebViews stay in memory: the least recently used ones are dropped (and reload when shown). */
    private fun trim() {
        val live = tabs.filter { it.web != null && it.id != currentId }.sortedByDescending { it.lastUsed }
        live.drop(MAX_LIVE - 1).forEach { it.capture(); it.destroyWeb() }
    }

    fun save() {
        val (privates, normal) = tabs.partition { it.private }
        val cur = normal.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        BrowserStore.saveSession(
            Session(normal.map { it.saved() }, cur, privates.map { it.saved() }, privates.indexOfFirst { it.id == currentId }),
        )
    }

    private fun BrowserTab.saved() = SavedTab(if (home) "" else url, title, desktop, backUrls())

    fun destroyAll() {
        save()
        tabs.forEach { it.destroyWeb() }
    }

    companion object {
        private const val MAX_LIVE = 6
    }
}
