package com.fastdownloader.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingCheckTest {
    @Test
    fun findsAStockPhraseRegardlessOfCase() {
        val hits = WritingCheck.find("Let's Delve into the topic.")
        assertEquals(listOf("delve"), hits.map { it.phrase.lowercase() })
    }

    @Test
    fun ignoresWordsThatOnlyContainAPhrase() {
        assertTrue(WritingCheck.find("The undelved mineral was found.").isEmpty())
    }

    @Test
    fun matchesCurlyApostrophes() {
        val hits = WritingCheck.find("In today’s fast-paced world, speed matters.")
        assertEquals(1, hits.size)
    }

    @Test
    fun reportsWhereEachPhraseSits() {
        val text = "Seamlessly done. In conclusion, fine."
        val hit = WritingCheck.find(text).single { it.phrase.equals("in conclusion", ignoreCase = true) }
        assertEquals("In conclusion", text.substring(hit.start, hit.end))
    }

    @Test
    fun plainProseHasNoHits() {
        assertTrue(WritingCheck.find("The download finished at 3 pm on Tuesday.").isEmpty())
    }

    @Test
    fun scriptHasThePatternInPlace() {
        val script = WritingCheck.script()
        assertTrue(script.contains("'giu'"))
        assertTrue(!script.contains("__PATTERN__"))
    }
}
