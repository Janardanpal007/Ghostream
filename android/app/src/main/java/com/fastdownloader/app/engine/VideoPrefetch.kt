package com.fastdownloader.app.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reading a video's qualities with yt-dlp takes a few seconds, so it starts as soon as a video page is open in the
 * browser: by the time Download is tapped, the choices are usually ready. One video at a time (the newest page
 * wins), kept for 30 minutes, which is well inside the time YouTube's links stay valid.
 */
object VideoPrefetch {
    private const val FRESH_MS = 30 * 60_000L
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private class Entry(val job: Deferred<VideoDetails?>, val at: Long)
    private val cache = LinkedHashMap<String, Entry>()
    @Volatile private var latest = ""

    /** The same video under its different addresses (watch?v=, youtu.be/, shorts/, extra parameters). */
    fun key(url: String): String {
        Regex("""(?:v=|youtu\.be/|shorts/|live/|embed/)([\w-]{11})""").find(url)?.let { return "yt:" + it.groupValues[1] }
        return url.substringBefore('#')
    }

    fun start(ctx: Context, url: String, headers: Map<String, String>) {
        val k = key(url)
        latest = k
        synchronized(cache) {
            val old = cache[k]
            if (old != null && System.currentTimeMillis() - old.at < FRESH_MS && !(old.job.isCompleted && old.job.getCompleted() == null)) return
            val app = ctx.applicationContext
            cache[k] = Entry(
                scope.async {
                    // Moved on to another video while this one waited its turn: skip it.
                    if (latest != k) return@async null
                    runCatching { Ytdlp.inspect(app, url, headers, Ytdlp.cookieFile(app, url, headers["Cookie"])) }.getOrNull()
                },
                System.currentTimeMillis(),
            )
            while (cache.size > 8) cache.remove(cache.keys.first())
        }
    }

    /** The video's qualities if they're read or being read (waits for that), else null. */
    suspend fun ready(url: String): VideoDetails? {
        val e = synchronized(cache) { cache[key(url)] } ?: return null
        if (System.currentTimeMillis() - e.at >= FRESH_MS) return null
        return withTimeoutOrNull(60_000) { e.job.await() }
    }
}
