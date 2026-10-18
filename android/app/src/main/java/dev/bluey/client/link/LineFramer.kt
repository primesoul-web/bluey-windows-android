package dev.bluey.client.link

import java.io.ByteArrayOutputStream

/**
 * Splits a TCP byte stream into newline-terminated packet lines, dropping any line that
 * doesn't decode — the same loop as LineConnection.receive in Shared/GooglyLink.swift.
 * Partial lines are buffered until their newline arrives.
 */
class LineFramer {
    private val pending = ByteArrayOutputStream()

    /** Feeds a chunk of bytes; returns every complete, valid packet it completes. */
    fun feed(chunk: ByteArray, length: Int = chunk.size): List<Packet> {
        val buffer = if (pending.size() == 0) {
            if (length == chunk.size) chunk else chunk.copyOf(length)
        } else {
            val buffered = pending.toByteArray()
            pending.reset()
            bufferOf(buffered, chunk, length)
        }

        val packets = ArrayList<Packet>(2)
        var start = 0
        for (i in buffer.indices) {
            if (buffer[i] != '\n'.code.toByte()) continue
            val line = buffer.copyOfRange(start, i)
            start = i + 1
            PacketCodec.tryDecode(line.decodeToString())?.let { packets.add(it) }
        }
        if (start < buffer.size) {
            pending.write(buffer, start, buffer.size - start)
        }
        return packets
    }

    private fun bufferOf(buffered: ByteArray, chunk: ByteArray, length: Int): ByteArray {
        val merged = ByteArray(buffered.size + length)
        System.arraycopy(buffered, 0, merged, 0, buffered.size)
        System.arraycopy(chunk, 0, merged, buffered.size, length)
        return merged
    }
}
