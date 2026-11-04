package com.fastdownloader.app.browser

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Bookmark(val url: String, val title: String, val addedAt: Long = System.currentTimeMillis())

@Serializable
data class Visit(val url: String, val title: String, val at: Long = System.currentTimeMillis())

/** An open tab as saved for the next start. Private tabs are never saved. */
@Serializable
data class SavedTab(
    val url: String,
    val title: String,
    val desktop: Boolean = false,
    /** The pages before this one, oldest first, so Back still works after the app was closed. */
    val back: List<String> = emptyList(),
)

@Serializable
data class Session(val tabs: List<SavedTab> = emptyList(), val current: Int = 0)

/** Totals for the start page. */
@Serializable
data class BrowserStats(val blocked: Long = 0, val httpsUpgrades: Long = 0)

/**
 * Bookmarks, history, the open tabs and the start page's counters, in the app's private folder
 * (the browser folder's JSON files, written atomically a moment after each change).
 */
object BrowserStore {
    private const val HISTORY_MAX = 2000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: File
    private val pending = HashMap<String, Job>()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()
    private val _history = MutableStateFlow<List<Visit>>(emptyList())
    val history: StateFlow<List<Visit>> = _history.asStateFlow()
    private val _stats = MutableStateFlow(BrowserStats())
    val stats: StateFlow<BrowserStats> = _stats.asStateFlow()
    var session = Session()
        private set

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "browser").apply { mkdirs() }
        _bookmarks.value = read("bookmarks.json", ListSerializer(Bookmark.serializer())) ?: emptyList()
        _history.value = read("history.json", ListSerializer(Visit.serializer())) ?: emptyList()
        _stats.value = read("stats.json", BrowserStats.serializer()) ?: BrowserStats()
        session = read("session.json", Session.serializer()) ?: Session()
    }

    // ---------- bookmarks ----------

    /** Bookmarks from another browser's file; ones already here are skipped. Returns how many were added. */
    fun addBookmarks(found: List<Pair<String, String>>): Int {
        val have = _bookmarks.value.map { it.url }.toMutableSet()
        val add = found.filter { have.add(it.first) }.map { (url, title) -> Bookmark(url, title.ifBlank { url }) }
        if (add.isEmpty()) return 0
        _bookmarks.update { it + add }
        saveLater("bookmarks.json") { json.encodeToString(ListSerializer(Bookmark.serializer()), _bookmarks.value) }
        return add.size
    }

    fun isBookmarked(url: String) = _bookmarks.value.any { it.url == url }

    fun toggleBookmark(url: String, title: String): Boolean {
        val now = !isBookmarked(url)
        _bookmarks.update { list -> if (now) listOf(Bookmark(url, title.ifBlank { url })) + list else list.filterNot { it.url == url } }
        saveLater("bookmarks.json") { json.encodeToString(ListSerializer(Bookmark.serializer()), _bookmarks.value) }
        return now
    }

    fun removeBookmark(url: String) {
        _bookmarks.update { list -> list.filterNot { it.url == url } }
        saveLater("bookmarks.json") { json.encodeToString(ListSerializer(Bookmark.serializer()), _bookmarks.value) }
    }

    fun renameBookmark(url: String, title: String) {
        _bookmarks.update { list -> list.map { if (it.url == url) it.copy(title = title) else it } }
        saveLater("bookmarks.json") { json.encodeToString(ListSerializer(Bookmark.serializer()), _bookmarks.value) }
    }

    // ---------- history ----------

    /** A page visited in a normal tab. A reload or the same page again moves it to the top. */
    fun addVisit(url: String, title: String) {
        if (!url.startsWith("http")) return
        _history.update { list ->
            val rest = list.filterNot { it.url == url }
            (listOf(Visit(url, title.ifBlank { url })) + rest).take(HISTORY_MAX)
        }
        saveHistory()
    }

    /** The title arrives after the page starts loading. */
    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        var changed = false
        _history.update { list ->
            list.map { if (it.url == url && it.title != title) { changed = true; it.copy(title = title) } else it }
        }
        if (changed) saveHistory()
    }

    fun removeVisit(url: String) {
        _history.update { list -> list.filterNot { it.url == url } }
        saveHistory()
    }

    fun clearHistory() {
        _history.value = emptyList()
        saveHistory()
    }

    private fun saveHistory() = saveLater("history.json") { json.encodeToString(ListSerializer(Visit.serializer()), _history.value) }

    /** Most visited sites for the start page: one entry per site, most visits first. */
    fun topSites(n: Int): List<Visit> {
        val bySite = _history.value.groupBy { Omnibox.hostOf(it.url)?.removePrefix("www.") ?: it.url }
        return bySite.entries.sortedByDescending { it.value.size }.take(n).map { e ->
            val first = e.value.first()
            val root = first.url.substringBefore("://") + "://" + (Omnibox.hostOf(first.url) ?: "") + "/"
            Visit(root, e.key, first.at)
        }
    }

    /** Address bar suggestions from bookmarks and history. */
    fun suggest(text: String, n: Int = 6): List<Visit> {
        val q = text.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val fromBookmarks = _bookmarks.value.filter { it.url.lowercase().contains(q) || it.title.lowercase().contains(q) }.map { Visit(it.url, it.title, it.addedAt) }
        val fromHistory = _history.value.filter { it.url.lowercase().contains(q) || it.title.lowercase().contains(q) }
        return (fromBookmarks + fromHistory).distinctBy { it.url }.take(n)
    }

    // ---------- session and counters ----------

    fun saveSession(s: Session) {
        session = s
        saveLater("session.json", 400) { json.encodeToString(Session.serializer(), s) }
    }

    fun countBlocked(n: Int = 1) {
        _stats.update { it.copy(blocked = it.blocked + n) }
        saveLater("stats.json", 3000) { json.encodeToString(BrowserStats.serializer(), _stats.value) }
    }

    fun countHttpsUpgrade() {
        _stats.update { it.copy(httpsUpgrades = it.httpsUpgrades + 1) }
        saveLater("stats.json", 3000) { json.encodeToString(BrowserStats.serializer(), _stats.value) }
    }

    // ---------- files ----------

    private fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(serializer, f.readText()) }.getOrNull()
    }

    /** Several quick changes are written once. */
    @Synchronized
    private fun saveLater(name: String, delayMs: Long = 800, text: () -> String) {
        if (!::dir.isInitialized) return
        pending[name]?.cancel()
        pending[name] = scope.launch {
            delay(delayMs)
            val content = text()
            synchronized(this@BrowserStore) {
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(content)
                if (!tmp.renameTo(File(dir, name))) {
                    File(dir, name).delete()
                    tmp.renameTo(File(dir, name))
                }
            }
        }
    }
}
