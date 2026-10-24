package dev.bluey.client.link

/**
 * Addresses that failed greeting verification (v0.3.4) — the other Bluey fork on
 * this Wi-Fi. A rejected address isn't retried until the TTL passes, so the
 * reconnect loop can't ping-pong between our PC and the impostor forever. Pure
 * in-memory: a restart forgets, which is fine — verification just runs again.
 */
class HostBlacklist(
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 5 * 60_000L,
) {
    private val blocked = mutableMapOf<String, Long>()

    /** Marks an address as not-our-PC until the TTL expires. */
    fun remember(address: String) {
        blocked[address] = now() + ttlMs
    }

    /** True while the address is blacklisted; expired entries clean themselves up. */
    fun isBlocked(address: String): Boolean {
        val until = blocked[address] ?: return false
        if (until <= now()) {
            blocked.remove(address)
            return false
        }
        return true
    }

    /** How many addresses are currently blocked (for tests and status display). */
    val size: Int get() = blocked.size
}
