package dev.bluey.client.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing for typed PC addresses (port addition D12) — the way onto networks where
 * mDNS can't see the PC, like USB tethering.
 */
class ManualHostTest {

    @Test
    fun `a bare ip gets the default port`() {
        assertEquals(ManualHost("192.168.42.196", 51826), ManualHost.parse("192.168.42.196"))
    }

    @Test
    fun `an ip with a port keeps it`() {
        assertEquals(ManualHost("192.168.42.196", 51826), ManualHost.parse("192.168.42.196:51826"))
        assertEquals(ManualHost("10.0.0.5", 65000), ManualHost.parse("10.0.0.5:65000"))
    }

    @Test
    fun `a hostname works too`() {
        assertEquals(ManualHost("DESKTOP-ROOM", 51826), ManualHost.parse("DESKTOP-ROOM"))
        assertEquals(ManualHost("my-pc.local", 1234), ManualHost.parse("my-pc.local:1234"))
    }

    @Test
    fun `surrounding spaces are trimmed`() {
        assertEquals(ManualHost("192.168.42.196", 51826), ManualHost.parse("  192.168.42.196:51826  "))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(ManualHost.parse(""))
        assertNull(ManualHost.parse("   "))
        assertNull(ManualHost.parse(":51826"))
        assertNull(ManualHost.parse("192.168.42.196:"))
        assertNull(ManualHost.parse("192.168.42.196:notaport"))
        assertNull(ManualHost.parse("192.168.42.196:0"))
        assertNull(ManualHost.parse("192.168.42.196:65536"))
        assertNull(ManualHost.parse("fe80::1")) // bare IPv6 needs brackets — out of scope
    }
}
