package com.fastdownloader.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A server that limits connections (HTTP 503/429) slows a download down instead of failing it. */
class HttpBusyTest {
    @Test
    fun `503 and 429 mean busy, other errors don't`() {
        assertTrue(Net.isBusy(503))
        assertTrue(Net.isBusy(429))
        for (code in listOf(200, 206, 403, 404, 500, 502)) assertFalse(code.toString(), Net.isBusy(code))
    }

    @Test
    fun `connections step aside while others keep downloading`() {
        val crew = HttpTask.Crew(8)
        repeat(7) { assertTrue(crew.tryRetire()) }
        assertEquals(1, crew.working)
    }

    @Test
    fun `the last connection never steps aside`() {
        val crew = HttpTask.Crew(1)
        assertFalse(crew.tryRetire())
        assertEquals(1, crew.working)
    }

    @Test
    fun `when all connections are refused at once, exactly one stays`() {
        // 32 connections all get 503 at the same moment.
        val crew = HttpTask.Crew(32)
        val start = CountDownLatch(1)
        val retired = AtomicInteger()
        val threads = (1..32).map { thread { start.await(); if (crew.tryRetire()) retired.incrementAndGet() } }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(31, retired.get())
        assertEquals(1, crew.working)
    }

    @Test
    fun `a finished connection leaving lets nobody else retire past the last one`() {
        val crew = HttpTask.Crew(2)
        crew.leave() // one finished its work
        assertFalse(crew.tryRetire())
    }

    @Test
    fun `a resting connection that rejoins counts as working again`() {
        val crew = HttpTask.Crew(3)
        assertTrue(crew.tryRetire())
        assertTrue(crew.tryRetire())
        assertFalse(crew.tryRetire()) // one must stay
        crew.rejoin()
        assertEquals(2, crew.working)
        assertTrue(crew.tryRetire()) // refused again: may rest again, the other still works
        assertEquals(1, crew.working)
    }

    @Test
    fun `the last connection keeps waiting while others rest, until nothing arrives for too long`() {
        var clock = 0L
        val crew = HttpTask.Crew(2) { clock }
        assertFalse(crew.othersMayReturn()) // nobody resting: the last one may give up
        assertTrue(crew.tryRetire())
        assertTrue(crew.othersMayReturn())
        clock = HttpTask.STALL_MS
        assertFalse(crew.othersMayReturn()) // stuck for too long: fail
        crew.progressed()
        assertTrue(crew.othersMayReturn())
        crew.rejoin()
        assertFalse(crew.othersMayReturn())
    }

    @Test
    fun `busy wait follows the server's Retry-After, capped at 30 seconds`() {
        assertEquals(5_000L, HttpTask.busyDelayMs(1, "5"))
        assertEquals(30_000L, HttpTask.busyDelayMs(1, "120"))
        assertEquals(0L, HttpTask.busyDelayMs(3, "0"))
    }

    @Test
    fun `without Retry-After the wait doubles from 2 seconds up to 30`() {
        assertEquals(2_000L, HttpTask.busyDelayMs(1, null))
        assertEquals(4_000L, HttpTask.busyDelayMs(2, null))
        assertEquals(16_000L, HttpTask.busyDelayMs(4, null))
        assertEquals(30_000L, HttpTask.busyDelayMs(5, null))
        assertEquals(30_000L, HttpTask.busyDelayMs(HttpTask.BUSY_RETRIES, null))
        // A date in Retry-After isn't a number: fall back to the doubling wait.
        assertEquals(2_000L, HttpTask.busyDelayMs(1, "Wed, 21 Oct 2026 07:28:00 GMT"))
    }
}
