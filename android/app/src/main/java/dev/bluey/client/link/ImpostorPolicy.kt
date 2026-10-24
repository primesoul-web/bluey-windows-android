package dev.bluey.client.link

/**
 * The decisions that keep the phone off the other Bluey fork (v0.3.5) — pure logic,
 * separate from NsdManager so it can be tested like everything else in this package.
 *
 * The situation these rules encode (2026-10-24/25): two PC apps on one Wi-Fi both
 * advertise `_googly._tcp` under the SAME instance name (the PC's hostname), ours
 * on [GooglyService.DEFAULT_PORT] with the TXT marker `go=pc`, theirs on another
 * port without it. Android's NsdManager can only resolve one instance per name,
 * and with both answering it hands out whichever replied first — 0.3.4's greeting
 * verification then correctly rejected the impostor every time, but the resolver
 * kept handing the impostor back, so the phone never landed on our PC at all
 * ("not even connecting", 2026-10-25). The fix is to stop treating mDNS as a coin
 * that might flip: an instance without our marker is the other fork, and the other
 * fork runs on the SAME HOST as ours — so our default port is one deterministic
 * dial away. The greeting check stays as the final gate either way.
 */
object ImpostorPolicy {

    /** True when a resolved service's TXT attributes carry our `go=pc` marker. */
    fun hasOurMarker(attributes: Map<String, ByteArray>?): Boolean {
        val value = attributes?.get(GooglyService.MARKER_KEY) ?: return false
        return value.toString(Charsets.UTF_8) == GooglyService.MARKER_VALUE
    }

    /**
     * Which port to dial after an mDNS resolve. A resolved instance that carries
     * our marker — or already sits on our default port — is dialed as resolved.
     * Anything else is almost certainly the other fork having won the name race:
     * dial our default port on the same host instead of connecting to it.
     */
    fun portAfterResolve(hasMarker: Boolean, resolvedPort: Int): Int =
        if (hasMarker || resolvedPort == GooglyService.DEFAULT_PORT) resolvedPort
        else GooglyService.DEFAULT_PORT

    /**
     * The port to try immediately after a "not-ours" greeting rejection, or null
     * when the rejected port already was our default (nothing left to try there —
     * the regular retry loop takes over). Same reasoning as [portAfterResolve]:
     * the impostor shares a host with our PC.
     */
    fun alternateAfterRejection(rejectedPort: Int): Int? =
        if (rejectedPort == GooglyService.DEFAULT_PORT) null else GooglyService.DEFAULT_PORT
}
