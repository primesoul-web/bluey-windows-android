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
 *  - an explicit stop flag replaces Swift's link-identity guard against post-stop retries.
 */
class PcLink(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences("bluey", Context.MODE_PRIVATE) }

    val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
    val pcName = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Every PC running Bluey on this Wi-Fi, by its advertised name. */
    val pcs = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val currentPc = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** The PC picked in the panel. Remembered, so the phone goes back to it on launch. */
    val preferredPc = kotlinx.coroutines.flow.MutableStateFlow<String?>(prefs.getString(PREF_PREFERRED, null))

    /** Commands from the PC, like "wake" and "sleep". Invoked on the main dispatcher. */
    var onCommand: ((String) -> Unit)? = null

    /** Face updates from the PC. Invoked on the main dispatcher. */
    var onFace: ((FaceState) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nsd by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val dispatcher = PacketDispatcher(::send) { command -> onCommand?.invoke(command) }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val services = LinkedHashMap<String, NsdServiceInfo>()
    private var link: SocketLink? = null
    private var retryJob: Job? = null
    private var watchdogJob: Job? = null
    @Volatile private var stopping = false
    @Volatile private var lastFaceAt = 0L
    @Volatile private var resolveGeneration = 0

    fun start() {
        stopping = false
        if (discoveryListener != null) return
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

    fun stop() {
        stopping = true
        retryJob?.cancel()
        watchdogJob?.cancel()
        discoveryListener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Exception) { /* already stopped */ }
        }
        discoveryListener = null
        services.clear()
        refreshPcs()
        link?.let { l -> link = null; l.close() }
        connected.value = false
    }

    /** Switches to another PC and remembers the choice. */
    fun choose(name: String) {
        preferredPc.value = name
        prefs.edit().putString(PREF_PREFERRED, name).apply()
        if (currentPc.value != name) link?.close()
        connectIfNeeded()
    }

    fun send(packet: Packet) {
        link?.send(packet)
    }

    /** Sends a request to the PC and calls back with its reply (null if the PC went away). */
    fun request(packet: Packet, done: (Packet?) -> Unit) {
        val l = link
        if (l == null || !connected.value) {
            done(null)
            return
        }
        dispatcher.request(packet, done)
    }

    private fun refreshPcs() {
        pcs.value = services.keys.sorted()
    }

    private fun scheduleRetry() {
        if (stopping) return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(1500)
            if (stopping) return@launch
            if (discoveryListener == null) start() else connectIfNeeded()
        }
    }

    private fun connectIfNeeded() {
        if (stopping || link != null || services.isEmpty()) return
        val preferred = preferredPc.value
        val name = services.keys.firstOrNull { it == preferred } ?: services.keys.first()
        currentPc.value = name
        val info = services[name] ?: return
        resolve(info)
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
                connect(host, serviceInfo.port)
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
        val l = SocketLink(host, port, deviceName())
        link = l
        lastFaceAt = System.currentTimeMillis()
        l.onReady = {
            connected.value = true
            startWatchdog()
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
        watchdogJob?.cancel()
        dispatcher.failAll()
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

    private companion object {
        const val PREF_PREFERRED = "preferredPc"
    }
}
