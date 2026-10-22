package dev.bluey.client.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CallID request/reply correlation, the port of MacLink's `waiting` map: the requester
 * mints a UUID; the replier echoes it verbatim; a reply with an unknown id falls through
 * to the command handler (which ignores unknown commands).
 *
 * Port addition (v0.3.3): requests may carry a reply timeout. A request that gets no
 * reply in time is answered with null — the same shape as "the PC went away". Without
 * this, a request sent into a phone→PC black hole (a link where the PC's faces still
 * arrive but the phone's writes never reach it — seen in the wild 2026-10-22) hangs
 * until LiveVoice's vague 20-second wake watchdog, and the user learns nothing. Tools
 * keep the old behavior (no timeout): a slow look_at_screen or web_research may
 * legitimately take longer than any fixed budget.
 */
class PacketDispatcher(
    private val send: (Packet) -> Unit,
    private val onCommand: (String) -> Unit,
    /** Where timeout timers run; null disables timeouts entirely (tests, tools). */
    private val timeoutScope: CoroutineScope? = null,
) {
    private val waiting = ConcurrentHashMap<String, Pending>()

    private class Pending(val done: (Packet?) -> Unit, val timer: Job?)

    val outstanding: Int get() = waiting.size

    /** Sends a request with a fresh callID; done receives the reply (null if the PC went
     * away, or — when timeoutMs is set — if no reply arrived in time). */
    fun request(packet: Packet, timeoutMs: Long = 0, done: (Packet?) -> Unit) {
        val id = UUID.randomUUID().toString()
        val timer: Job? = if (timeoutMs > 0) {
            timeoutScope?.launch {
                delay(timeoutMs)
                // The race with a same-instant reply is resolved by the map: whoever
                // removes the entry first wins, and done fires exactly once.
                waiting.remove(id)?.let { pending ->
                    pending.timer?.cancel()
                    pending.done(null)
                }
            }
        } else {
            null
        }
        waiting[id] = Pending(done, timer)
        send(packet.copy(callID = id))
    }

    /** Routes one incoming packet: reply, command, or drop. */
    fun handle(packet: Packet) {
        val id = packet.callID
        if (id != null) {
            val pending = waiting.remove(id)
            if (pending != null) {
                pending.timer?.cancel()
                pending.done(packet)
                return
            }
        }
        val command = packet.command ?: return
        onCommand(command)
    }

    /** Fails every outstanding request (the PC went away). */
    fun failAll() {
        val callbacks = waiting.keys.mapNotNull { waiting.remove(it) }
        callbacks.forEach {
            it.timer?.cancel()
            it.done(null)
        }
    }
}
