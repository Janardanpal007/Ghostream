package com.fastdownloader.app.engine

import okhttp3.Call
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import kotlin.concurrent.thread

/** How a task ended. */
sealed class Outcome {
    data object Done : Outcome()
    data object Stopped : Outcome()
    data class Failed(val message: String, val network: Boolean = false) : Outcome()
}

interface Task {
    fun start()
    fun stop()
}

/**
 * Downloads one file over HTTP in parallel parts (Range requests), all written into one
 * "<name>.fdpart" file at their own offsets. When a connection finishes early it takes half of
 * the largest part still left, so all connections stay busy until the end. Progress is kept in
 * [Item.segments], so a paused or interrupted download continues where it stopped, even after
 * the app or phone restarts.
 */
class HttpTask(
    private val item: Item,
    private val connections: Int,
    private val throttle: Throttle,
    private val onEnd: (Outcome) -> Unit,
) : Task {
    @Volatile private var stopped = false
    private val calls = Collections.synchronizedSet(mutableSetOf<Call>())
    private val busy = Collections.synchronizedSet(mutableSetOf<Seg>())

    override fun start() {
        thread(name = "http-${item.id}") {
            val outcome = try {
                run()
            } catch (e: Throwable) {
                if (stopped) Outcome.Stopped else failure(e)
            }
            onEnd(outcome)
        }
    }

    override fun stop() {
        stopped = true
        synchronized(calls) { calls.forEach { it.cancel() } }
    }

    private fun run(): Outcome {
        val part = File(item.dir, item.fileName + PART)
        part.parentFile?.mkdirs()

        if (item.resumable && item.total > 0) {
            // Continuing: make sure the file on the server is still the same one.
            if (item.received > 0) {
                val p = runCatching { Net.probe(item.url, item.headers) }.getOrNull()
                val changed = p != null && (p.size != item.total ||
                    (item.etag != null && p.etag != null && p.etag != item.etag) ||
                    (item.lastModified != null && p.lastModified != null && p.lastModified != item.lastModified) ||
                    !p.resumable)
                if (changed && p != null) {
                    // A different file now: start over with what the server says about it.
                    reset()
                    part.delete()
                    synchronized(item) {
                        item.total = p.size
                        item.resumable = p.resumable
                        item.etag = p.etag
                        item.lastModified = p.lastModified
                    }
                    if (!item.resumable || item.total <= 0) return single(part)
                } else if (!part.exists()) {
                    reset()
                }
            }
            synchronized(item) {
                if (item.segments.isEmpty()) item.segments.addAll(split(item.total, connections))
            }
            return segmented(part)
        }
        return single(part)
    }

    private fun reset() = synchronized(item) {
        item.segments.clear()
        item.received = 0
    }

    private fun segmented(part: File): Outcome {
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() != item.total) raf.setLength(item.total)
            val channel = raf.channel
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val crew = Crew(connections)
            val workers = (1..connections).map {
                thread(name = "seg-${item.id}-$it") {
                    // Stepped aside after the server refused it; tries again after a pause, since many
                    // servers let an extra connection in now and then, and each one adds speed.
                    var resting = false
                    try {
                        while (!stopped) {
                            if (resting) {
                                if (!restThenRejoin(crew)) break
                                resting = false
                            }
                            val seg = nextSegment() ?: break
                            try {
                                resting = !fetch(seg, channel, crew)
                            } finally {
                                busy.remove(seg) // a resting connection's part goes back for the others
                            }
                        }
                    } catch (e: Throwable) {
                        if (!stopped) {
                            errors += e
                            stop() // one part failed for good: stop the others and report it
                        }
                    } finally {
                        if (!resting) crew.leave()
                    }
                }
            }
            workers.forEach { it.join() }
            channel.force(false)
            val left = synchronized(item) { item.segments.sumOf { it.remaining } }
            return when {
                left == 0L -> finish(part)
                errors.isNotEmpty() -> failure(errors.first())
                else -> Outcome.Stopped
            }
        }
    }

    /**
     * Waits [REJOIN_MS] (plus a little, so resting connections don't all knock at once), then rejoins the crew.
     * False when there's no point any more: the download was stopped or nothing is left to fetch.
     */
    private fun restThenRejoin(crew: Crew): Boolean {
        val until = System.currentTimeMillis() + REJOIN_MS + (Math.random() * REJOIN_JITTER_MS).toLong()
        while (System.currentTimeMillis() < until) {
            if (stopped || remaining() == 0L) return false
            Thread.sleep(REST_STEP_MS)
        }
        if (stopped || remaining() == 0L) return false
        crew.rejoin()
        return true
    }

    private fun remaining() = synchronized(item) { item.segments.sumOf { it.remaining } }

    /** A part nobody is working on, or else half of the biggest one in progress. */
    private fun nextSegment(): Seg? = synchronized(item) {
        item.segments.firstOrNull { it.remaining > 0 && it !in busy }?.let { busy += it; return it }
        val biggest = item.segments.filter { it in busy }.maxByOrNull { it.remaining } ?: return null
        if (biggest.remaining < 2 * MIN_SPLIT) return null
        val mid = biggest.pos + biggest.remaining / 2
        val tail = Seg(mid, biggest.end)
        biggest.end = mid - 1
        item.segments += tail
        busy += tail
        tail
    }

    /**
     * Downloads one part, retrying a few times if the connection drops. Returns false when this connection
     * steps aside: the server is refusing extra connections (503/429) while the others still download.
     */
    private fun fetch(seg: Seg, channel: FileChannel, crew: Crew): Boolean {
        var attempt = 0
        var busyAttempt = 0
        while (!stopped && seg.remaining > 0) {
            try {
                val from = synchronized(item) { seg.pos }
                val to = synchronized(item) { seg.end }
                val call = Net.client().newCall(Net.request(item.url, item.headers, "bytes=$from-$to"))
                calls += call
                try {
                    call.execute().use { r ->
                        if (r.code != 206) {
                            if (Net.isBusy(r.code)) throw ServerBusy(r.code, r.header("Retry-After"))
                            if (r.code == 200) throw FatalError("The server stopped accepting partial downloads. Remove this download and add it again.")
                            if (r.code in 400..499 && r.code != 408 && r.code != 429) throw FatalError(Net.httpError(r.code))
                            throw IOException(Net.httpError(r.code))
                        }
                        val input = r.body.byteStream()
                        val buf = ByteArray(BUF)
                        while (!stopped) {
                            val n = input.read(buf)
                            if (n < 0) break
                            var write: Int
                            var pos: Long
                            synchronized(item) {
                                write = minOf(n.toLong(), seg.remaining).toInt()
                                pos = seg.pos
                            }
                            if (write <= 0) break // another connection took over the rest of this part
                            var off = 0
                            while (off < write) off += channel.write(ByteBuffer.wrap(buf, off, write - off), pos + off)
                            synchronized(item) {
                                seg.done += write
                                item.received += write
                            }
                            attempt = 0
                            busyAttempt = 0
                            throttle.take(write)
                            if (write < n) break
                        }
                    }
                } finally {
                    calls -= call
                }
            } catch (e: FatalError) {
                throw e
            } catch (e: ServerBusy) {
                if (stopped) return true
                // The site allows fewer connections than we opened: this one steps aside, the rest carry on.
                if (crew.tryRetire()) return false
                // The last connection waits for the site instead of failing the download.
                if (++busyAttempt > BUSY_RETRIES) throw FatalError(Net.httpError(e.code))
                Thread.sleep(busyDelayMs(busyAttempt, e.retryAfter))
            } catch (e: IOException) {
                if (stopped) return true
                if (++attempt > RETRIES) throw e
                Thread.sleep(minOf(1000L shl (attempt - 1), 15_000L))
            }
        }
        return true
    }

    /** No resume support or unknown size: one connection, from the start. */
    private fun single(part: File): Outcome {
        reset()
        part.delete()
        val call = Net.client().newCall(Net.request(item.url, item.headers))
        calls += call
        call.execute().use { r ->
            if (!r.isSuccessful) return Outcome.Failed(Net.httpError(r.code))
            val length = r.body.contentLength()
            if (length > 0) synchronized(item) { item.total = length }
            part.outputStream().buffered(BUF).use { out ->
                val input = r.body.byteStream()
                val buf = ByteArray(BUF)
                while (!stopped) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    synchronized(item) { item.received += n }
                    throttle.take(n)
                }
            }
        }
        if (stopped) return Outcome.Stopped
        if (item.total > 0 && item.received != item.total) return Outcome.Failed("The connection closed before the whole file arrived.", network = true)
        synchronized(item) { item.total = item.received }
        return finish(part)
    }

    private fun finish(part: File): Outcome {
        val wanted = File(item.dir, item.fileName)
        val target = if (!wanted.exists()) wanted else Files.uniqueFile(File(item.dir), item.fileName, startAt = 2)
        if (!part.renameTo(target)) {
            // Android 11+ hides files other apps saved, so a name can be taken without us seeing it.
            val alt = Files.uniqueFile(File(item.dir), item.fileName, startAt = 2, taken = setOf(target.name))
            if (!part.renameTo(alt)) return Outcome.Failed("Couldn't save the file in ${item.dir}.")
            synchronized(item) { item.fileName = alt.name }
        } else {
            synchronized(item) { item.fileName = target.name }
        }
        return Outcome.Done
    }

    private fun failure(e: Throwable): Outcome = when (e) {
        is FatalError -> Outcome.Failed(e.message ?: "Download failed.")
        is UnknownHostException -> Outcome.Failed("No internet connection, or the site's address couldn't be found.", network = true)
        is SocketException, is InterruptedIOException -> Outcome.Failed("The connection was lost. It will continue when you're back online.", network = true)
        is IOException -> if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true)
            Outcome.Failed("Your phone is out of storage space.")
        else Outcome.Failed(e.message ?: "Download failed.", network = true)
        else -> Outcome.Failed(e.message ?: e.javaClass.simpleName)
    }

    private class FatalError(message: String) : IOException(message)

    /** The server is busy or limiting connections (HTTP 503/429). */
    private class ServerBusy(val code: Int, val retryAfter: String?) : IOException(Net.httpError(code))

    /**
     * How many connections are still downloading. One may step aside only while another keeps going,
     * so a download never ends up with nobody working on it.
     */
    class Crew(size: Int) {
        private var alive = size

        @Synchronized fun tryRetire(): Boolean = if (alive > 1) { alive--; true } else false

        @Synchronized fun leave() { alive-- }

        /** A resting connection tries again. */
        @Synchronized fun rejoin() { alive++ }

        @get:Synchronized val working: Int get() = alive
    }

    companion object {
        const val PART = ".fdpart"
        private const val BUF = 64 * 1024
        private const val MIN_SPLIT = 1L shl 20 // 1 MB
        private const val RETRIES = 6
        /** About 4 minutes for a busy server when only one connection is left. */
        const val BUSY_RETRIES = 10
        private const val BUSY_MAX_DELAY_MS = 30_000L
        /** How long a refused connection rests before trying again. */
        const val REJOIN_MS = 30_000L
        private const val REJOIN_JITTER_MS = 10_000L
        private const val REST_STEP_MS = 500L

        /** Wait before asking a busy server again: its Retry-After (seconds) if it gave one, else 2, 4, 8… s; at most 30 s. */
        fun busyDelayMs(attempt: Int, retryAfter: String?): Long {
            val asked = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
            val backoff = 2000L shl (attempt - 1).coerceIn(0, 10)
            return minOf(asked ?: backoff, BUSY_MAX_DELAY_MS)
        }

        /** Equal parts, but none smaller than 1 MB. */
        fun split(total: Long, connections: Int): List<Seg> {
            val n = maxOf(1, minOf(connections.toLong(), (total + MIN_SPLIT - 1) / MIN_SPLIT).toInt())
            val size = total / n
            return (0 until n).map { i ->
                val start = i * size
                Seg(start, if (i == n - 1) total - 1 else start + size - 1)
            }
        }
    }
}

object Files {
    /** "name.ext", or "name (2).ext", "name (3).ext"… if taken (also by an unfinished .fdpart). */
    fun uniqueFile(dir: File, name: String, startAt: Int = 1, taken: Set<String> = emptySet()): File {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = startAt
        while (true) {
            val candidate = if (i == 1) name else "$base ($i)$ext"
            val f = File(dir, candidate)
            if (!f.exists() && !File(dir, candidate + HttpTask.PART).exists() && candidate !in taken) return f
            i++
        }
    }
}
