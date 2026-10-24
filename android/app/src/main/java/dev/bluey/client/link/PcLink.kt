package dev.bluey.client.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Finds the PC app on the local network (NsdManager over _googly._tcp) and keeps a
 * connection open — the port of iOS/Sources/MacLink.swift: 1.5 s retry loop, preferred-PC
 * memory and auto-switching, hello/face/command handling, callID correlation.
 *
 * Deviations from the original (documented in PARITY.md):
 *  - a 10 s face watchdog stands in for NWConnection's 2 s TCP keepalive, which Android's
 *    plain sockets can't set per-socket;
 *  - an explicit stop flag replaces Swift's link-identity guard against post-stop retries;
 *  - port addition (D12): a typed PC address (USB tethering and other mDNS-blind
 *    networks) plus a loopback probe for `adb reverse tcp:51826 tcp:51826`;
 *  - port addition (v0.3.4): every link must pass greeting verification before it
 *    counts as connected, a verified address is remembered and tried first, and
 *    addresses that greet in the other fork's voice are blacklisted for a while —
 *    the "connected to the wrong Bluey" churn of 2026-10-24, where two PC apps
 *    shared one `_googly._tcp` name and mDNS handed them out like a coin flip;
 *  - port addition (v0.3.5): the coin flip is gone — a resolved instance without
 *    our TXT marker is the other fork, and since both apps run on the same host,
 *    the phone dials our default port there directly (and again right after a
 *    "not-ours" rejection). Verification-day follow-up to "not even connecting",
 *    where the resolver kept handing back the impostor and the blacklist could
 *    only refuse it, never route around it.
 */
class PcLink(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences("bluey", Context.MODE_PRIVATE) }

    val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
    val pcName = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Every PC running Bluey on this Wi-Fi, by its advertised name. */
    val pcs = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val currentPc = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** What a connection attempt is aimed at right now ("192.168.42.196:51826"),
     * for the tiny on-screen status while mDNS can't see anything. */
    val target = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** The PC picked in the panel. Remembered, so the phone goes back to it on launch. */
    val preferredPc = kotlinx.coroutines.flow.MutableStateFlow<String?>(prefs.getString(PREF_PREFERRED, null))

    /** A PC address typed by hand, remembered for mDNS-blind networks (USB tethering). */
    val manualPc = kotlinx.coroutines.flow.MutableStateFlow<ManualHost?>(
        prefs.getString(PREF_MANUAL, null)?.let { ManualHost.parse(it) })

    /** Commands from the PC, like "wake" and "sleep". Invoked on the main dispatcher. */
    var onCommand: ((String) -> Unit)? = null

    /** Face updates from the PC. Invoked on the main dispatcher. */
    var onFace: ((FaceState) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nsd by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val dispatcher = PacketDispatcher(::send, { command -> onCommand?.invoke(command) }, timeoutScope = scope)

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val services = LinkedHashMap<String, NsdServiceInfo>()
    private var link: SocketLink? = null
    private var retryJob: Job? = null
    private var watchdogJob: Job? = null
    private var nudgeJob: Job? = null
    private var loopbackTicks = 0
    @Volatile private var stopping = false
    @Volatile private var lastFaceAt = 0L
    @Volatile private var resolveGeneration = 0

    /** Addresses that greeted as the other fork — not retried for a while (v0.3.4). */
    private val blacklist = HostBlacklist()

    /** "<pcName>@<host>:<port>" of the last link that passed verification — tried
     * before mDNS, which is a coin flip between same-named services. Null once it
     * stops working (IP changed); re-earned on the next verified connection. */
    private var verifiedAddress: String? = prefs.getString(PREF_VERIFIED, null)

    /** True when the last link loss was a greeting rejection — retry fast (v0.3.4):
     * a wrong-PC rejection shouldn't cost the same 1.5 s as a dead network. */
    @Volatile private var fastRetry = false

    /** A deterministic next try lined up by a "not-ours" rejection (v0.3.5): our
     * default port on the same host. Set in [connect]'s onRejected, consumed by
     * [onLinkLost], which always runs right after — a dial beats a timer when the
     * impostor and our PC share a machine. */
    private var pendingAlternate: Pair<String, Int>? = null

    fun start() {
        stopping = false
        if (discoveryListener == null) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    discoveryListener = null
                    scheduleRetry()
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    discoveryListener = null
                }
                override fun onDiscoveryStopped(serviceType: String) {
                    discoveryListener = null
                }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    // serviceType arrives like "_googly._tcp." — the exact type plus variants all
                    // start with "_googly"; anything else on the network is ignored.
                    if (!serviceInfo.serviceType.startsWith("_googly")) return
                    services[serviceInfo.serviceName] = serviceInfo
                    refreshPcs()
                    // Move over when the picked PC shows up while linked to a different one.
                    val preferred = preferredPc.value
                    if (preferred != null && currentPc.value != preferred && pcs.value.contains(preferred)) {
                        link?.close()
                    }
                    connectIfNeeded()
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    services.remove(serviceInfo.serviceName)
                    refreshPcs()
                }
            }
            discoveryListener = listener
            try {
                nsd.discoverServices(GooglyService.TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: IllegalArgumentException) {
                // already discovering — the listener we just kept will be reused
            }
        }
        // mDNS finds nothing on USB tethering (and some Wi-Fis); keep the manual and
        // loopback paths ticking on their own — the NSD callbacks only fire on finds.
        nudgeJob?.cancel()
        nudgeJob = scope.launch {
            while (isActive && !stopping) {
                if (link == null && services.isEmpty()) connectIfNeeded()
                delay(1500)
            }
        }
    }

    fun stop() {
        stopping = true
        retryJob?.cancel()
        watchdogJob?.cancel()
        nudgeJob?.cancel()
        discoveryListener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Exception) { /* already stopped */ }
        }
        discoveryListener = null
        services.clear()
        refreshPcs()
        link?.let { l -> link = null; l.close() }
        target.value = null
        connected.value = false
    }

    /** Switches to another PC and remembers the choice. */
    fun choose(name: String) {
        preferredPc.value = name
        prefs.edit().putString(PREF_PREFERRED, name).apply()
        if (currentPc.value != name) link?.close()
        connectIfNeeded()
    }

    /** Saves (or clears, when blank) a typed PC address for mDNS-blind networks.
     * Returns false when the text can't be parsed — nothing is saved then. */
    fun setManualHost(address: String?): Boolean {
        val trimmed = address?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(PREF_MANUAL).apply()
            manualPc.value = null
            if (services.isEmpty()) link?.close() // drop a pending loopback probe; the nudge retires
            return true
        }
        val parsed = ManualHost.parse(trimmed) ?: return false
        prefs.edit().putString(PREF_MANUAL, trimmed).apply()
        manualPc.value = parsed
        if (services.isEmpty()) {
            link?.close()      // manual comes before the loopback probe from now on
            connectIfNeeded()
        }
        return true
    }

    fun send(packet: Packet) {
        link?.send(packet)
    }

    /** Sends a request to the PC and calls back with its reply (null if the PC went
     * away, or — with timeoutMs set — if no reply arrived in time). */
    fun request(packet: Packet, timeoutMs: Long = 0, done: (Packet?) -> Unit) {
        val l = link
        if (l == null || !connected.value) {
            done(null)
            return
        }
        dispatcher.request(packet, timeoutMs, done)
    }

    /** Drops the current link and starts a fresh one (port addition, v0.3.3). Used when
     * a request went unanswered on a link that claims to be healthy — a phone→PC black
     * hole, where the PC's face stream still arrives but the phone's writes never reach
     * it. The fresh connection is verified by the hello/face handshake, so anything sent
     * on it is either delivered or honestly fails. */
    fun forceReconnect() {
        if (stopping) return
        val l = link
        if (l != null) {
            link = null
            l.close() // its onClosed sees link !== l and stays quiet; we clean up here
        }
        connected.value = false
        watchdogJob?.cancel()
        target.value = null
        scheduleRetry()
    }

    private fun refreshPcs() {
        pcs.value = services.keys.sorted()
    }

    private fun scheduleRetry() {
        if (stopping) return
        retryJob?.cancel()
        val delayMs = if (fastRetry) 250L else 1_500L
        fastRetry = false
        retryJob = scope.launch {
            delay(delayMs)
            if (stopping) return@launch
            if (discoveryListener == null) start() else connectIfNeeded()
        }
    }

    private fun connectIfNeeded() {
        if (stopping || link != null) return
        // The last address that passed verification goes first (v0.3.4): on networks
        // where another app also claims _googly._tcp under the same name, mDNS
        // resolution is a coin flip between the two PCs — the remembered address
        // isn't. If it no longer works (the PC's IP changed) it's dropped after one
        // failed attempt and discovery takes over again.
        val sticky = verifiedAddress
        if (sticky != null) {
            val parsed = parseVerified(sticky)
            if (parsed == null || blacklist.isBlocked(addressOf(parsed))) {
                forgetVerified()
            } else {
                currentPc.value = parsed.first
                connect(parsed.second.host, parsed.second.port)
                return
            }
        }
        if (services.isNotEmpty()) {
            val preferred = preferredPc.value
            val name = services.keys.firstOrNull { it == preferred } ?: services.keys.first()
            currentPc.value = name
            val info = services[name] ?: return
            resolve(info)
            return
        }
        // No mDNS visibility (typical on USB tethering): the typed address first…
        val manual = manualPc.value
        if (manual != null) {
            currentPc.value = "Manual — $manual"
            connect(manual.host, manual.port)
            return
        }
        // …then a cheap loopback probe every ~7.5 s, so `adb reverse tcp:51826 tcp:51826`
        // on a USB cable just works (refused instantly when nothing listens there).
        if (++loopbackTicks >= 5) {
            loopbackTicks = 0
            currentPc.value = "USB (adb reverse)"
            connect("127.0.0.1", GooglyService.DEFAULT_PORT)
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        val generation = ++resolveGeneration
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (generation != resolveGeneration) return
                scheduleRetry()
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (generation != resolveGeneration) return
                val host = serviceInfo.host?.hostAddress ?: return scheduleRetry()
                // v0.3.5: a resolved instance without our TXT marker is the other
                // fork (same service name, their port). Both apps live on the same
                // host, so dial our default port there instead of the impostor —
                // mDNS stops being a coin flip our connection depends on. A
                // marker-carrying (or default-port) instance is dialed as resolved.
                val port = ImpostorPolicy.portAfterResolve(
                    ImpostorPolicy.hasOurMarker(serviceInfo.attributes), serviceInfo.port)
                connect(host, port)
            }
        }
        try {
            nsd.resolveService(info, listener)
        } catch (_: IllegalArgumentException) {
            scheduleRetry()
        }
        // resolveService can hang silently on some devices — recycle it after 3.5 s.
        scope.launch {
            delay(3500)
            if (generation == resolveGeneration && link == null && !stopping) {
                resolveGeneration++
                scheduleRetry()
            }
        }
    }

    private fun connect(host: String, port: Int) {
        val address = "$host:$port"
        if (blacklist.isBlocked(address)) {
            // Greeted as the other fork recently — don't waste an attempt or a
            // greeting timeout on it; the next candidate is a fast-retry away.
            scheduleRetry()
            return
        }
        val l = SocketLink(host, port, deviceName(), verifyGreeting = true)
        link = l
        target.value = address
        lastFaceAt = System.currentTimeMillis()
        l.onReady = {
            // Connected is NOT set here (v0.3.4): the peer must first greet in our
            // protocol's voice. onVerified does the honors; onClosed cleans up.
        }
        l.onVerified = { greeted ->
            if (link === l) {
                greeted?.let { pcName.value = it }
                rememberVerified("${pcName.value ?: currentPc.value ?: "PC"}@$address")
                connected.value = true
                startWatchdog()
            }
        }
        l.onRejected = { reason ->
            if (link === l) {
                if (reason == SocketLink.REASON_NOT_OURS) {
                    // Greeted in the other fork's voice. Blacklist it, and line up
                    // our default port on the same host as the immediate next try
                    // (v0.3.5) — the regular 250 ms retry can only re-resolve the
                    // name, which is exactly the coin flip that never flipped.
                    blacklist.remember(address)
                    if (verifiedAddress?.endsWith("@$address") == true) forgetVerified()
                    fastRetry = true
                    pendingAlternate = ImpostorPolicy.alternateAfterRejection(port)?.let { host to it }
                } else {
                    // Silent peer — could be our own PC late to greet (busy instant,
                    // GC pause, flaky Wi-Fi). Blacklisting our own PC for five
                    // minutes over that locked the phone out entirely (2026-10-25),
                    // so don't. Just drop a matching sticky address; discovery and
                    // the greeting check sort the rest out on the next attempt.
                    if (verifiedAddress?.endsWith("@$address") == true) forgetVerified()
                }
            }
        }
        l.onPacket = { packet -> handlePacket(packet) }
        l.onClosed = {
            if (link === l) onLinkLost()
        }
        l.start(scope)
    }

    private fun handlePacket(packet: Packet) {
        packet.hello?.let { pcName.value = it }
        packet.face?.let { face ->
            lastFaceAt = System.currentTimeMillis()
            scope.launch { onFace?.invoke(face) }
        }
        if (packet.command != null || packet.callID != null) {
            scope.launch { dispatcher.handle(packet) }
        }
    }

    private fun onLinkLost() {
        if (stopping) return
        connected.value = false
        pcName.value = null
        link = null
        target.value = null
        watchdogJob?.cancel()
        dispatcher.failAll()
        val alternate = pendingAlternate
        pendingAlternate = null
        if (alternate != null) {
            // v0.3.5: dial the deterministic alternate right away instead of
            // waiting out a retry timer.
            connect(alternate.first, alternate.second)
            return
        }
        scheduleRetry()
    }
    /**
     * The PC streams faces at >= 1 Hz while connected; ten seconds of silence means the
     * link is dead (the Android stand-in for NWConnection's 2 s keepalive).
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && link != null && connected.value) {
                if (System.currentTimeMillis() - lastFaceAt > 10_000) {
                    link?.close()
                    return@launch
                }
                delay(1_000)
            }
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: Build.MODEL

    /** "<pcName>@<host>:<port>" → (pcName, host:port), or null when unparsable. */
    private fun parseVerified(stored: String): Pair<String, ManualHost>? {
        val at = stored.lastIndexOf('@')
        if (at <= 0) return null
        val name = stored.substring(0, at)
        val host = ManualHost.parse(stored.substring(at + 1)) ?: return null
        return name to host
    }

    private fun addressOf(parsed: Pair<String, ManualHost>): String = parsed.second.toString()

    private fun rememberVerified(value: String) {
        verifiedAddress = value
        prefs.edit().putString(PREF_VERIFIED, value).apply()
    }

    private fun forgetVerified() {
        verifiedAddress = null
        prefs.edit().remove(PREF_VERIFIED).apply()
    }

    private companion object {
        const val PREF_PREFERRED = "preferredPc"
        const val PREF_MANUAL = "manualPc"
        const val PREF_VERIFIED = "verifiedPc"
    }
}
