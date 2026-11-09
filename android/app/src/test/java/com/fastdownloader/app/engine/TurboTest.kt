package com.fastdownloader.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboTest {
    private val list = Turbo.HEADER + "\n" +
        "# batch 1\n" +
        Turbo.hash(LIFETIME) + " 0\n" +
        Turbo.hash(DATED) + " 20100\n"

    @Test
    fun `a key from the list is valid for life`() {
        val r = Turbo.check(LIFETIME, Turbo.parseList(list), day = 20_000)
        assertTrue(r is Turbo.Result.Valid)
        assertTrue((r as Turbo.Result.Valid).lifetime)
    }

    @Test
    fun `spaces and dashes in a key are ignored`() {
        assertEquals(LIFETIME, Turbo.normalize(" Kq7#m-Xp2@w "))
        assertTrue(Turbo.check("Kq7#m Xp2@w", Turbo.parseList(list), day = 20_000) is Turbo.Result.Valid)
    }

    @Test
    fun `keys are case sensitive`() {
        assertTrue(Turbo.check(LIFETIME.uppercase(), Turbo.parseList(list), day = 20_000) is Turbo.Result.Invalid)
    }

    @Test
    fun `a pasted email gives just the key`() {
        assertEquals(LIFETIME, Turbo.findKey("Hi, your Ghostream Turbo key: Kq7#m-Xp2@w\nThanks!"))
        assertEquals(LIFETIME, Turbo.findKey("Kq7#mXp2@w"))
        assertEquals(LIFETIME, Turbo.findKey("Kq7#m Xp2@w."))
        assertEquals("", Turbo.findKey("first stars and UTR 123456789012"))
        assertEquals("", Turbo.findKey("no key here"))
        assertEquals("", Turbo.findKey("Kq7#m-Xp2@w and Rt5\$h-Bn9?z"))
    }

    @Test
    fun `a key not in the list is rejected`() {
        assertEquals(Turbo.Result.Invalid("This key isn't valid"), Turbo.check("Zz9!aBc3#d", Turbo.parseList(list)))
    }

    @Test
    fun `keys must be 10 key characters`() {
        for (text in listOf("", "Kq7#m", "Kq7#mXp2@wQ", "Kq7#mXp2@0", "Kq7#mXp2,w", "Kq7 #mXp2@w!")) {
            assertEquals(text, Turbo.Result.Invalid(Turbo.WRONG_LENGTH), Turbo.check(text, Turbo.parseList(list)))
        }
    }

    @Test
    fun `a dated key works until its last day, then expires`() {
        val entries = Turbo.parseList(list)
        assertTrue(Turbo.check(DATED, entries, day = 20_100) is Turbo.Result.Valid)
        assertEquals(Turbo.Result.Invalid("This key expired"), Turbo.check(DATED, entries, day = 20_101))
    }

    @Test
    fun `a revoked key (its line removed from the list) stops working`() {
        val revoked = list.lines().filterNot { it.startsWith(Turbo.hash(LIFETIME)) }.joinToString("\n")
        assertTrue(Turbo.check(LIFETIME, Turbo.parseList(revoked)) is Turbo.Result.Invalid)
    }

    @Test
    fun `a list without the header is refused, so an error page can't wipe the keys`() {
        assertNull(Turbo.parseList("<html>404</html>"))
        assertNull(Turbo.parseList(""))
        assertEquals(2, Turbo.parseList(list)?.size)
    }

    @Test
    fun `the hash is slow, one-way and matches the PC generator`() {
        val h = Turbo.hash(LIFETIME)
        assertEquals(64, h.length)
        assertFalse(h.contains(LIFETIME))
        // Same value the PC key generator prints for "hash Kq7#mXp2@w": both use PBKDF2-SHA256 with the same salt and rounds.
        assertEquals(GENERATOR_HASH_OF_LIFETIME, h)
    }

    @Test
    fun `free YouTube quality stops at 1080p and Turbo keeps the choice`() {
        assertEquals("hd1080", Turbo.youtubeChoice("hd1440", turbo = false))
        assertEquals("hd1080", Turbo.youtubeChoice("highest", turbo = false))
        assertEquals("hd720", Turbo.youtubeChoice("hd720", turbo = false))
        assertEquals("wifi", Turbo.youtubeChoice("wifi", turbo = false))
        assertEquals("highest", Turbo.youtubeChoice("highest", turbo = true))
    }

    @Test
    fun `free download limits are capped and Turbo gets the full range`() {
        val s = Settings(segments = 32, maxConcurrent = 10, torrentConnections = 1000)
        assertEquals(Turbo.FREE_PARTS, Turbo.parts(s, turbo = false))
        assertEquals(Turbo.FREE_AT_ONCE, Turbo.atOnce(s, turbo = false))
        assertEquals(Turbo.FREE_TORRENT_CONNECTIONS, Turbo.torrentConnections(s, turbo = false))
        assertEquals(32, Turbo.parts(s, turbo = true))
        assertEquals(10, Turbo.atOnce(s, turbo = true))
        assertEquals(1000, Turbo.torrentConnections(s, turbo = true))
    }

    @Test
    fun `video downloads above 1080p need Turbo`() {
        assertTrue(Turbo.allowsHeight(1080, turbo = false))
        assertFalse(Turbo.allowsHeight(2160, turbo = false))
        assertTrue(Turbo.allowsHeight(2160, turbo = true))
    }

    private companion object {
        const val LIFETIME = "Kq7#mXp2@w"
        const val DATED = "Rt5\$hBn9?z"
        const val GENERATOR_HASH_OF_LIFETIME = "6ca9cf2736774a76d3f321243f6e298a679b86157fcf459d211634ed89e4efd2"
    }
}
