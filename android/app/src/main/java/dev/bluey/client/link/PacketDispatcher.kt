package dev.bluey.client.link

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CallID request/reply correlation, the port of MacLink's `waiting` map: the requester
 * mints a UUID; the replier echoes it verbatim; a reply with an unknown id falls through
 * to the command handler (which ignores unknown commands).
 */
class PacketDispatcher(
    private val send: (Packet) -> Unit,
    private val onCommand: (String) -> Unit,
) {
    private val waiting = ConcurrentHashMap<String, (Packet?) -> Unit>()

    val outstanding: Int get() = waiting.size

    /** Sends a request with a fresh callID; done receives the reply (null if the PC went away). */
    fun request(packet: Packet, done: (Packet?) -> Unit) {
        val id = UUID.randomUUID().toString()
        waiting[id] = done
        send(packet.copy(callID = id))
    }

    /** Routes one incoming packet: reply, command, or drop. */
    fun handle(packet: Packet) {
        val id = packet.callID
        if (id != null) {
            val done = waiting.remove(id)
            if (done != null) {
                done(packet)
                return
            }
        }
        val command = packet.command ?: return
        onCommand(command)
    }

    /** Fails every outstanding request (the PC went away). */
    fun failAll() {
        val callbacks = waiting.keys.mapNotNull { waiting.remove(it) }
        callbacks.forEach { it(null) }
    }
}
