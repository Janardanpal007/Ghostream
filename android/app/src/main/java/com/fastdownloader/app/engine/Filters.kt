package com.fastdownloader.app.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The filter lists uBlock Origin uses by default. Copies of the bundled ones ship inside the app,
 * so blocking works from the first launch; all of them are refreshed from their publishers every
 * few days (through the proxy, if one is set).
 */
object Filters {
    /**
     * [dns]: a host list made for DNS blocking, whose exceptions only undo its own blocks (see [AdBlock.parse]).
     * [cookies]: a cookie-banner list, used while "Hide cookie banners" is on.
     */
    data class FilterList(
        val id: String, val name: String, val url: String, val bundled: Boolean,
        val dns: Boolean = false, val cookies: Boolean = false,
    )

    val LISTS = listOf(
        FilterList("ublock-filters", "uBlock filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt", true),
        FilterList("ublock-privacy", "uBlock filters: Privacy", "https://ublockorigin.github.io/uAssets/filters/privacy.txt", true),
        // Scam, fake-download and malware sites, and the pop-ups that lead to them.
        FilterList("ublock-badware", "uBlock filters: Badware risks", "https://ublockorigin.github.io/uAssets/filters/badware.txt", true),
        // Exceptions that keep sites working (consent dialogs, sign-ins, video players), as uBlock Origin has by default.
        FilterList("ublock-unbreak", "uBlock filters: Unbreak", "https://ublockorigin.github.io/uAssets/filters/unbreak.txt", true),
        FilterList("ublock-quick-fixes", "uBlock filters: Quick fixes", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt", true),
        FilterList("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt", true),
        FilterList("easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", true),
        FilterList(
            "peter-lowe", "Peter Lowe's ad and tracking server list",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext", false,
        ),
        // Whole ad, tracker and phone-maker telemetry hosts (TikTok, Xiaomi, Oppo/Realme, Apple, Yandex…) that the
        // page-oriented lists above only block on some paths. Downloaded, not bundled: it's large.
        FilterList("adguard-dns", "AdGuard DNS filter", "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt", false, dns = true),
        // Cookie consent banners: hidden, or answered with "reject" where the site needs an answer. Downloaded.
        FilterList(
            "easylist-cookies", "EasyList Cookie Notices",
            "https://ublockorigin.github.io/uAssets/thirdparties/easylist-cookies.txt", false, cookies = true,
        ),
        FilterList(
            "ublock-cookies", "uBlock filters: Cookie Notices",
            "https://ublockorigin.github.io/uAssets/filters/annoyances-cookies.txt", false, cookies = true,
        ),
    )

    /** Trackers the lists above only partly cover (they block one file, or nothing, on these). */
    private val OWN_RULES = listOf(
        "||snap.licdn.com/li.lms-analytics^", // LinkedIn Insight tag
        "||redditstatic.com/ads^", // Reddit Pixel
        "||admob.googleapis.com^", // AdMob
    )

    data class Status(val loading: Boolean = false, val networkRules: Int = 0, val cosmeticRules: Int = 0, val updatedAt: Long = 0, val message: String? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile var engine: AdBlock? = null
        private set
    private const val EVERY = 4 * 24 * 3600_000L
    private const val TAG = "Filters"
    private const val SNAPSHOT_FILE = "engine.bin"
    private const val NANOS_PER_MS = 1_000_000L

    /** The snapshot key of the engine now in memory; [load] skips the work when nothing has changed since. */
    private var loadedKey: String? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "filters").apply { mkdirs() }

    /**
     * Builds the blocker from the lists (downloaded copy if there is one, else the bundled one). When the lists and
     * settings are the same as for the engine already in memory this does nothing; when they match the saved
     * snapshot ([SNAPSHOT_FILE]) the engine is read from it instead of parsing the lists again.
     */
    @Synchronized
    fun load(ctx: Context) {
        _status.value = _status.value.copy(loading = true)
        val key = snapshotKey(ctx)
        val current = engine
        val ab = if (current != null && key == loadedKey) current else restoreOrParse(ctx, key)
        engine = ab
        loadedKey = key
        val updated = LISTS.mapNotNull { File(dir(ctx), "${it.id}.txt").takeIf { f -> f.exists() }?.lastModified() }.maxOrNull() ?: 0
        _status.value = Status(false, ab.networkRules, ab.cosmeticRules, updated)
    }

    /** Loads the saved snapshot if it fits [key]; otherwise parses the lists and saves a new snapshot. */
    private fun restoreOrParse(ctx: Context, key: String): AdBlock {
        val file = File(dir(ctx), SNAPSHOT_FILE)
        val started = System.nanoTime()
        val restored = if (file.exists()) {
            runCatching { file.inputStream().use { AdBlockSnapshot.read(it, key) } }
                .onFailure { Log.w(TAG, "Snapshot unreadable, parsing the lists instead", it) }
                .getOrNull()
        } else null
        if (restored != null) {
            Log.i(TAG, "Engine restored from snapshot in ${(System.nanoTime() - started) / NANOS_PER_MS} ms")
            return restored
        }
        val parsed = parseLists(ctx)
        Log.i(TAG, "Lists parsed in ${(System.nanoTime() - started) / NANOS_PER_MS} ms")
        writeSnapshot(parsed, key, file)
        return parsed
    }

    private fun parseLists(ctx: Context): AdBlock {
        val loaded = usedLists().mapNotNull { list ->
            val f = File(dir(ctx), "${list.id}.txt")
            when {
                f.exists() -> f.readText()
                list.bundled -> runCatching { ctx.assets.open("filters/${list.id}.txt").bufferedReader().readText() }.getOrNull()
                else -> null
            }?.let { list to it }
        }
        val (dnsLists, lists) = loaded.partition { it.first.dns }
        return AdBlock.parse(lists.map { it.second } + ownList(ctx) + OWN_RULES.joinToString("\n"), dnsLists.map { it.second })
    }

    /** Saves [ab] next to the lists, through a temporary file so a half-written snapshot is never read. */
    private fun writeSnapshot(ab: AdBlock, key: String, file: File) {
        val tmp = File(file.parentFile, "$SNAPSHOT_FILE.tmp")
        runCatching {
            tmp.outputStream().use { AdBlockSnapshot.write(ab, key, it) }
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "rename failed" } }
        }.onFailure {
            Log.w(TAG, "Could not save the engine snapshot", it)
            tmp.delete()
        }
    }

    /** The lists in use: the cookie-banner lists only while "Hide cookie banners" is on. */
    private fun usedLists(): List<FilterList> = LISTS.filter { Engine.settings.hideCookieBanners || !it.cookies }

    /**
     * What a snapshot depends on: the format, this install of the app (the bundled lists and trackers.json come with
     * it), and for each list in use either its downloaded file (size and date) or the bundled copy.
     */
    private fun snapshotKey(ctx: Context): String {
        val installed = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val parts = usedLists().map { list ->
            val f = File(dir(ctx), "${list.id}.txt")
            if (f.exists()) "${list.id}:${f.length()}:${f.lastModified()}" else "${list.id}:bundled"
        }
        return "v${AdBlockSnapshot.FORMAT_VERSION};app=$installed;" + parts.joinToString(";")
    }

    /** Ghostream's own ad and tracker domains (shared with the browser extension). */
    private fun ownList(ctx: Context): String = runCatching {
        val rules = Json.parseToJsonElement(ctx.assets.open("trackers.json").bufferedReader().readText()).jsonArray
        rules.flatMap { r ->
            r.jsonObject["condition"]?.jsonObject?.get("requestDomains")?.jsonArray?.map { "||${it.jsonPrimitive.content}^" } ?: emptyList()
        }.joinToString("\n")
    }.getOrDefault("")

    /** Downloads fresh copies of the lists when they're older than a few days (or now, with [force]). */
    fun updateIfDue(ctx: Context, force: Boolean = false): String {
        val d = dir(ctx)
        val newest = LISTS.mapNotNull { File(d, "${it.id}.txt").takeIf { f -> f.exists() }?.lastModified() }.maxOrNull() ?: 0
        if (!force && System.currentTimeMillis() - newest < EVERY && LISTS.all { File(d, "${it.id}.txt").exists() }) {
            if (engine == null) load(ctx)
            return "Up to date"
        }
        _status.value = _status.value.copy(loading = true, message = "Updating filter lists…")
        var ok = 0
        for (list in LISTS) {
            runCatching {
                val text = Net.client().newCall(Net.request(list.url, emptyMap())).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body.string()
                }
                // Don't replace a good list with an error page.
                if (text.length < 1000 || text.trimStart().startsWith("<")) error("not a filter list")
                val tmp = File(d, "${list.id}.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(d, "${list.id}.txt")) || run { File(d, "${list.id}.txt").delete(); tmp.renameTo(File(d, "${list.id}.txt")) }
                ok++
            }
        }
        load(ctx)
        val msg = if (ok == LISTS.size) "All ${LISTS.size} lists updated" else "$ok of ${LISTS.size} lists updated (the rest keep their last copy)"
        _status.value = _status.value.copy(message = msg)
        return msg
    }
}
