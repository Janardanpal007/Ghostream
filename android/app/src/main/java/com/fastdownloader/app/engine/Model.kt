package com.fastdownloader.app.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File

enum class Kind { HTTP, TORRENT, VIDEO }

enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, ERROR }

/** One part of a file, downloaded by its own connection. [end] is inclusive. */
@Serializable
class Seg(var start: Long, var end: Long, var done: Long = 0) {
    val pos get() = start + done
    val remaining get() = end - start + 1 - done
}

/**
 * A download as saved in downloads.json. Fields are changed by the task that runs it,
 * always while holding the item's lock (synchronized(item)).
 */
@Serializable
class Item(
    val id: String,
    var url: String,
    val kind: Kind,
    var fileName: String,
    var dir: String,
    var category: String,
    var status: Status = Status.QUEUED,
    var total: Long = -1,
    var received: Long = 0,
    var resumable: Boolean = false,
    var segments: MutableList<Seg> = mutableListOf(),
    var etag: String? = null,
    var lastModified: String? = null,
    var headers: Map<String, String> = emptyMap(),
    /** Video: the yt-dlp format to download, and "mp3"/"m4a" for audio only. */
    var format: String? = null,
    var audio: String? = null,
    /** Video: progress reported by yt-dlp when the size isn't known yet (0–1). */
    var fraction: Float = 0f,
    var error: String? = null,
    /** Failed because the network dropped: start again by itself once it's back. */
    var retryOnNetwork: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    var finishedAt: Long? = null,
) {
    val file get() = File(dir, fileName)
}

@Serializable
data class Settings(
    val categorize: Boolean = true,
    val maxConcurrent: Int = 3,
    val segments: Int = 8,
    val speedLimitKBps: Int = 0,
    val wifiOnly: Boolean = false,
    val clipboardMonitor: Boolean = true,
    val notifyOnComplete: Boolean = true,
    val videoContainer: String = "mp4",
    val torrentConnections: Int = 150,
    val torrentExtraTrackers: Boolean = true,
    val torrentVpnOnly: Boolean = false,
    val proxyType: String = "none", // none | http | socks5
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUser: String = "",
    val proxyPass: String = "",
    val privacyHeaders: Boolean = true,
    val stripTracking: Boolean = true,
    val blockAds: Boolean = true,
    /** No Shorts on YouTube in the Browser tab: hidden from pages, and Shorts links open as normal videos. */
    val hideShorts: Boolean = true,
    /** Sites where the user turned ad blocking off (the browser's shield button). */
    val adblockAllow: List<String> = emptyList(),
    /** Sites where the user allowed pop-ups (the "Pop-up blocked" bar). */
    val popupAllow: List<String> = emptyList(),
    val keepHistory: Boolean = true,
    val userName: String = "",
    /** Look for a newer Ghostream every few hours and notify. */
    val checkUpdates: Boolean = true,
    /** The newest version already announced with a notification. */
    val updateNotified: String = "",

    // ---------- browser ----------
    /** Search engine for the address bar (see browser/Omnibox.kt). */
    val searchEngine: String = "duckduckgo",
    /** A page to open for new tabs and Home instead of the built-in start page ("" = start page). */
    val homepageUrl: String = "",
    /** Reopen the last session's tabs when the app starts. */
    val restoreTabs: Boolean = true,
    val showHomeStats: Boolean = true,
    val showTopSites: Boolean = true,
    /** system | light | dark */
    val themeMode: String = "system",
    /** Colour theme id from THEME_COLORS ("classic" = black/white buttons). */
    val themeColor: String = "classic",
    /** Page text size, percent. */
    val textZoom: Int = 100,
    /** New tabs ask for desktop pages. */
    val desktopMode: Boolean = false,
    /** Load http:// pages over https:// when the site supports it. */
    val httpsUpgrade: Boolean = true,
    /** all | block3p | none */
    val cookiePolicy: String = "block3p",
    /** Per-site Shields exceptions. */
    val httpsOffSites: List<String> = emptyList(),
    val scriptBlockSites: List<String> = emptyList(),
    val thirdPartyCookieSites: List<String> = emptyList(),
    /** Full-screen video shape: original | fit | fill | zoom */
    val videoFit: String = "fill",
    /** Turn sideways for full-screen video. */
    val autoLandscape: Boolean = true,
    /** Leaving the app during a full-screen video keeps it playing in a small window. */
    val autoPip: Boolean = true,
    /** YouTube picture quality on Wi-Fi: "wifi" (the best 1080p-or-lower the phone decodes), "highest", "hd1440", "hd1080", "hd720" or "auto". */
    val youtubeQuality: String = "wifi",
    /** YouTube picture quality on mobile data: "hd1440", "hd1080", "hd720" (default), "highest" or "auto". */
    val youtubeQualityMobile: String = "hd720",
    /** Links to files (zip, apk, pdf…) open the download panel instead of a blank page. */
    val detectFileLinks: Boolean = true,
    /** Browser downloads start without the panel when the file needs no choices. */
    val quickDownload: Boolean = false,

    // ---------- Ghostream Turbo ----------
    /** The 10-character Turbo key the user activated ("" = free). */
    val turboKey: String = "",
    /** Its one-way hash, as listed on the website; what [Turbo.current] checks. */
    val turboHash: String = "",
    /** The website's turbo-keys.txt, saved at activation and refreshed once a day (works offline). */
    val turboList: List<String> = emptyList(),
) {
    val proxyOn get() = proxyType != "none" && proxyHost.isNotBlank() && proxyPort in 1..65535
}

/** What the screens show for one download: a copy, so drawing never races with the download threads. */
data class ItemView(
    val id: String,
    val kind: Kind,
    val url: String,
    val fileName: String,
    val category: String,
    val status: Status,
    val total: Long,
    val received: Long,
    val fraction: Float,
    val speed: Long,
    val etaSeconds: Long,
    val error: String?,
    val note: String?,
    val path: String,
    val resumable: Boolean,
    val finishedAt: Long?,
)

/** downloads.json and settings.json in the app's private folder, written atomically. */
class Store(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun loadItems(): MutableList<Item> = read("downloads.json") { json.decodeFromString<MutableList<Item>>(it) } ?: mutableListOf()
    /** Each item is copied under its lock, since its download may be changing it. */
    fun saveItems(items: List<Item>) = write(
        "downloads.json",
        JsonArray(items.map { synchronized(it) { json.encodeToJsonElement(Item.serializer(), it) } }).toString(),
    )
    fun loadSettings(): Settings = read("settings.json") { json.decodeFromString<Settings>(it) } ?: Settings()
    fun saveSettings(s: Settings) = write("settings.json", json.encodeToString(s))

    private fun <T> read(name: String, parse: (String) -> T): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return runCatching { parse(f.readText()) }.getOrNull()
    }

    @Synchronized
    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) {
            File(dir, name).delete()
            tmp.renameTo(File(dir, name))
        }
    }
}
