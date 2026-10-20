package dev.bluey.client.realtime

/**
 * LiveVoice's audio ports, as interfaces so the state machine runs in unit tests
 * without Android media hardware. [MicStreamer] and [ChirpPlayer] are the real ones.
 */
interface MicSource {
    val active: Boolean

    /** Raw PCM16 chunks as they were spoken. */
    var onChunk: ((ShortArray, Int) -> Unit)?

    /** Registers a consumer of base64 PCM chunks (the Realtime input stream). */
    fun onAudioBase64(sender: (String) -> Unit)

    fun start()

    fun stop()
}

interface ChirpSink {
    /** Buffers still queued or playing (the original's pendingBuffers). */
    val pendingBuffers: Int

    /** Loudness of the chirp playing right now, 0…1. */
    val level: Double

    fun play(samples: FloatArray, volume: Float, onDone: () -> Unit)

    fun stopAll()
}
