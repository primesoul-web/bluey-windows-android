package dev.bluey.client.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rejection blacklist (v0.3.4): impostor addresses don't get retried forever. */
class HostBlacklistTest {

    @Test
    fun `a remembered address is blocked`() {
        val list = HostBlacklist(now = { 1_000L }, ttlMs = 60_000L)
        assertFalse(list.isBlocked("10.0.0.5:47821"))
        list.remember("10.0.0.5:47821")
        assertTrue(list.isBlocked("10.0.0.5:47821"))
    }

    @Test
    fun `other addresses are untouched`() {
        val list = HostBlacklist(now = { 1_000L }, ttlMs = 60_000L)
        list.remember("10.0.0.5:47821")
        assertFalse(list.isBlocked("10.0.0.5:51826"))
    }

    @Test
    fun `the block expires after the ttl`() {
        var now = 1_000L
        val list = HostBlacklist(now = { now }, ttlMs = 60_000L)
        list.remember("10.0.0.5:47821")
        now = 61_000L
        assertFalse(list.isBlocked("10.0.0.5:47821"))
    }

    @Test
    fun `re-remembering refreshes the ttl`() {
        var now = 1_000L
        val list = HostBlacklist(now = { now }, ttlMs = 60_000L)
        list.remember("10.0.0.5:47821")
        now = 50_000L // 10 s before expiry…
        list.remember("10.0.0.5:47821") // …but it greets as the other fork again
        now = 100_000L // past the ORIGINAL expiry
        assertTrue(list.isBlocked("10.0.0.5:47821"))
        assertEquals(1, list.size)
    }
}
