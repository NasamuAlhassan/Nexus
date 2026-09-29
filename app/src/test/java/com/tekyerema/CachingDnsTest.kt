package com.tekyerema

import com.tekyerema.core.CachingDns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

/** A20e 2026-09-29: Private DNS took 5–11 s a lookup on the campus Wi-Fi; every new connection waited on it. */
class CachingDnsTest {
    private val a = InetAddress.getByAddress("api.groq.com", byteArrayOf(104, 18, 38, 236.toByte()))
    private val b = InetAddress.getByAddress("api.groq.com", byteArrayOf(104, 18, 39, 1))

    @Test fun a_fresh_answer_is_reused_without_a_lookup() {
        val calls = AtomicInteger()
        var now = 0L
        val dns = CachingDns(system = { calls.incrementAndGet(); listOf(a) }, clock = { now },
            freshMs = { 300_000L }, staleMs = { 3_600_000L }, budgetMs = { 100L })
        assertEquals(listOf(a), dns.lookup("api.groq.com"))
        now = 200_000L
        assertEquals(listOf(a), dns.lookup("API.groq.com"))
        assertEquals(1, calls.get())
    }

    @Test fun a_slow_lookup_falls_back_to_the_last_good_address_then_refreshes() {
        var now = 0L
        var slow = false
        var answer = listOf(a)
        val dns = CachingDns(system = { if (slow) Thread.sleep(600); answer }, clock = { now },
            freshMs = { 1_000L }, staleMs = { 3_600_000L }, budgetMs = { 100L })
        dns.lookup("api.groq.com")
        now = 10_000L; slow = true; answer = listOf(b)
        val t0 = System.currentTimeMillis()
        assertEquals("the stale answer, at once", listOf(a), dns.lookup("api.groq.com"))
        assertTrue(System.currentTimeMillis() - t0 < 500)
        Thread.sleep(800)   // the background lookup finished and refreshed the cache
        assertEquals(listOf(b), dns.lookup("api.groq.com"))
    }

    @Test fun with_nothing_known_the_lookup_is_waited_for_and_failures_surface() {
        val dns = CachingDns(system = { h -> if (h == "nowhere.invalid") throw UnknownHostException(h) else listOf(a) },
            clock = { 0L }, freshMs = { 1L }, staleMs = { 2L }, budgetMs = { 1L })
        assertEquals(listOf(a), dns.lookup("api.groq.com"))
        try { dns.lookup("nowhere.invalid"); throw AssertionError("expected UnknownHostException") }
        catch (_: UnknownHostException) {}
    }

    @Test fun forget_makes_the_next_request_look_it_up_again() {
        val calls = AtomicInteger()
        val dns = CachingDns(system = { calls.incrementAndGet(); listOf(a) }, clock = { 0L },
            freshMs = { 300_000L }, staleMs = { 3_600_000L }, budgetMs = { 100L })
        dns.lookup("api.groq.com"); dns.forget("api.groq.com"); dns.lookup("api.groq.com")
        assertEquals(2, calls.get())
    }
}
