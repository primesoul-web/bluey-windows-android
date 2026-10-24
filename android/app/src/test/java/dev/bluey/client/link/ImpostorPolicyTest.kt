package dev.bluey.client.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The impostor-routing decisions (v0.3.5): mDNS on this Wi-Fi resolves the shared
 * `_googly._tcp` name to whichever of two same-host PC apps answered first, so the
 * phone must not treat that answer as the only candidate. A marker-less instance
 * (or a "not-ours" greeting rejection) routes to our default port on the same host
 * instead. Pure logic, no NsdManager — that's the point of the split.
 */
class ImpostorPolicyTest {

    // --- hasOurMarker ---

    @Test fun `null attributes carry no marker`() {
        assertFalse(ImpostorPolicy.hasOurMarker(null))
    }

    @Test fun `empty attributes carry no marker`() {
        assertFalse(ImpostorPolicy.hasOurMarker(emptyMap()))
    }

    @Test fun `our exact marker is recognized`() {
        assertTrue(ImpostorPolicy.hasOurMarker(mapOf(GooglyService.MARKER_KEY to "pc".toByteArray())))
    }

    @Test fun `a marker key with the wrong value is not ours`() {
        assertFalse(ImpostorPolicy.hasOurMarker(mapOf("go" to "impostor".toByteArray())))
    }

    @Test fun `unrelated TXT keys are ignored`() {
        // The other fork's TXT (if any) doesn't carry go=pc.
        assertFalse(ImpostorPolicy.hasOurMarker(mapOf("app" to "bluey-other".toByteArray())))
    }

    @Test fun `our marker plus unrelated keys is still ours`() {
        assertTrue(ImpostorPolicy.hasOurMarker(mapOf(
            "something" to "else".toByteArray(),
            GooglyService.MARKER_KEY to GooglyService.MARKER_VALUE.toByteArray())))
    }

    // --- portAfterResolve ---

    @Test fun `marker-carrying instance is dialed as resolved`() {
        assertEquals(51826, ImpostorPolicy.portAfterResolve(hasMarker = true, resolvedPort = 51826))
        assertEquals(54321, ImpostorPolicy.portAfterResolve(hasMarker = true, resolvedPort = 54321))
    }

    @Test fun `default-port instance is dialed as resolved even without a marker`() {
        // Our own pre-0.3.4 PC, or anything already on the well-known port.
        assertEquals(51826, ImpostorPolicy.portAfterResolve(hasMarker = false, resolvedPort = 51826))
    }

    @Test fun `marker-less instance on a foreign port routes to our default port`() {
        // The other fork won the name race (47821 is their observed port).
        assertEquals(51826, ImpostorPolicy.portAfterResolve(hasMarker = false, resolvedPort = 47821))
    }

    // --- alternateAfterRejection ---

    @Test fun `rejecting the impostor lines up our default port`() {
        assertEquals(51826, ImpostorPolicy.alternateAfterRejection(47821))
    }

    @Test fun `rejecting our own default port leaves no alternate`() {
        // Something squatted 51826 and greeted wrongly — nothing deterministic
        // left to try on that host; the regular retry loop takes over.
        assertNull(ImpostorPolicy.alternateAfterRejection(51826))
    }
}
