package com.fastdownloader.app.engine

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Ghostream Turbo: paid extras unlocked by a 10-character key (letters, numbers, symbols) from the developer.
 *
 * Keys are made in batches on the developer's PC (TurboKeyGen). The keys stay private; the website publishes only
 * a slow one-way hash of each (turbo-keys.txt: "<hash> <expiresDay>", expiresDay 0 = lifetime, days since 1970).
 * Activating hashes the typed key and looks for it in that list, so the app needs internet once; after that the
 * saved list is used offline. Removing a line from the website's list switches that key off at the next check.
 */
object Turbo {
    const val FREE_PARTS = 8
    const val FREE_AT_ONCE = 3
    const val FREE_TORRENT_CONNECTIONS = 200
    const val FREE_MAX_HEIGHT = 1080
    const val PAGE = "https://janardanpal007.github.io/Ghostream/#turbo"
    const val LIST_URL = "https://janardanpal007.github.io/Ghostream/turbo-keys.txt"
    /** First line of turbo-keys.txt; a download without it (an error page) is ignored. */
    const val HEADER = "# ghostream-turbo-keys v1"
    const val KEY_LENGTH = 10

    /**
     * Key characters (must match TurboKeyGen.java): letters without I, L, O, l, o; numbers 2-9; ten symbols.
     * Look-alikes (0/O/o, 1/l/I) are left out so a key can be read back. 65 characters, so 65^10 ≈ 1.3 × 10^18 keys.
     */
    const val KEY_SYMBOLS = "@#$%&*+=?!"
    const val KEY_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789$KEY_SYMBOLS"
    /** KEY_CHARS as a regex character class. */
    private const val KEY_CLASS = "A-HJKMNP-Za-km-np-z2-9@#\$%&*+=?!"

    // Must match TurboKeyGen.java.
    private const val SALT = "ghostream-turbo-v1"
    private const val ROUNDS = 200_000
    private const val DAY_MS = 86_400_000L
    const val WRONG_LENGTH = "A Turbo key has 10 characters: letters, numbers and symbols"

    sealed interface Result {
        data class Valid(val expiresDay: Long) : Result {
            val lifetime get() = expiresDay == 0L
        }
        data class Invalid(val reason: String) : Result
    }

    fun today(): Long = System.currentTimeMillis() / DAY_MS

    /** A key in pasted text, e.g. "Your key: Kq7#m-Xp2@w." → "Kq7#mXp2@w"; "" unless there's exactly one. */
    fun findKey(text: String): String {
        val c = "[$KEY_CLASS]"
        val found = Regex("(?<!$c)$c{5}[ -]?$c{5}(?!$c)").findAll(text)
            .map { normalize(it.value) }.filter(::looksLikeKey).distinct().toList()
        return found.singleOrNull().orEmpty()
    }

    /** What was typed without spaces and dashes ("Kq7#m-Xp2@w" → "Kq7#mXp2@w"), or "" when it isn't a key. */
    fun normalize(text: String): String {
        val compact = text.filterNot { it.isWhitespace() || it == '-' }
        return compact.takeIf { it.length == KEY_LENGTH && it.all { ch -> ch in KEY_CHARS } }.orEmpty()
    }

    /** Every key has a capital, a small letter, a number and a symbol, so ordinary words in an email never match. */
    private fun looksLikeKey(k: String) =
        k.any(Char::isUpperCase) && k.any(Char::isLowerCase) && k.any(Char::isDigit) && k.any { it in KEY_SYMBOLS }

    /** PBKDF2-SHA256 of the key, as 64 hex characters. Slow on purpose (a fraction of a second): call off the main thread. */
    fun hash(key: String): String {
        val spec = PBEKeySpec(key.toCharArray(), SALT.toByteArray(), ROUNDS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** turbo-keys.txt → hash to expiresDay, or null when it isn't a key list. */
    fun parseList(text: String): Map<String, Long>? {
        val lines = text.lines().map { it.trim() }
        if (lines.firstOrNull() != HEADER) return null
        return lines.drop(1).filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            val expires = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            parts[0].lowercase() to expires
        }.toMap()
    }

    /** Checks a typed key against the list (slow: hashes the key). */
    fun check(key: String, list: Map<String, Long>?, day: Long = today()): Result {
        val k = normalize(key)
        if (k.isEmpty()) return Result.Invalid(WRONG_LENGTH)
        if (list == null) return Result.Invalid("Couldn't get the key list. Connect to the internet and try again")
        return status(hash(k), list, day)
    }

    /** An already hashed key against the list (fast). */
    private fun status(keyHash: String, list: Map<String, Long>, day: Long): Result {
        val expires = list[keyHash] ?: return Result.Invalid("This key isn't valid")
        if (expires != 0L && day > expires) return Result.Invalid("This key expired")
        return Result.Valid(expires)
    }

    // ---------- what the app uses ----------

    @Volatile private var cached: Triple<String, List<String>, Result>? = null

    /** The activated key (its saved hash) against the saved list. Fast, so screens can call it. */
    fun current(s: Settings = Engine.settings): Result {
        cached?.let { (h, l, res) -> if (h == s.turboHash && l == s.turboList) return res }
        val result = when {
            s.turboHash.isEmpty() -> Result.Invalid("No key")
            else -> status(s.turboHash, parseList(s.turboList.joinToString("\n")).orEmpty(), today())
                .let { if (it == Result.Invalid("This key isn't valid")) Result.Invalid("This key was switched off") else it }
        }
        return result.also { cached = Triple(s.turboHash, s.turboList, it) }
    }

    val active: Boolean get() = current() is Result.Valid

    /** Downloads the key list from the website (null when offline or it isn't a list). Network: off the main thread. */
    fun downloadList(): String? = runCatching {
        Net.client().newCall(Net.request(LIST_URL, mapOf("Accept" to "text/plain"))).execute().use { r ->
            if (!r.isSuccessful) null else r.body.string().takeIf { parseList(it) != null }
        }
    }.getOrNull()

    /**
     * Activates a typed key: gets the list, checks the key, and saves both when it's valid.
     * Network and a slow hash: call off the main thread.
     */
    fun activate(key: String): Result {
        val text = downloadList()
        val result = check(key, text?.let(::parseList))
        if (result is Result.Valid && text != null) {
            val keyHash = hash(normalize(key))
            Engine.updateSettings { it.copy(turboKey = normalize(key), turboHash = keyHash, turboList = text.lines()) }
        }
        return result
    }

    /** Keeps the saved list current (the update check calls this), so a removed key stops working. */
    fun refreshList() {
        val text = downloadList() ?: return
        if (text.lines() != Engine.settings.turboList) Engine.updateSettings { it.copy(turboList = text.lines()) }
    }

    fun deactivate() = Engine.updateSettings { it.copy(turboKey = "", turboHash = "") }

    fun parts(s: Settings, turbo: Boolean = active) = s.segments.coerceIn(1, if (turbo) 32 else FREE_PARTS)
    fun atOnce(s: Settings, turbo: Boolean = active) = s.maxConcurrent.coerceIn(1, if (turbo) 10 else FREE_AT_ONCE)
    fun torrentConnections(s: Settings, turbo: Boolean = active) =
        s.torrentConnections.coerceIn(20, if (turbo) 1000 else FREE_TORRENT_CONNECTIONS)

    val TURBO_QUALITIES = setOf("hd1440", "highest")

    /** YouTube above 1080p is Turbo; without it, 2K and Highest play at 1080p. */
    fun youtubeChoice(choice: String, turbo: Boolean = active) =
        if (!turbo && choice in TURBO_QUALITIES) "hd1080" else choice

    fun allowsHeight(height: Int, turbo: Boolean = active) = turbo || height <= FREE_MAX_HEIGHT
}
