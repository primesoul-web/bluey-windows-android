package dev.bluey.client.realtime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * The mic: one 16 kHz PCM16 mono [AudioRecord] (voice-communication source, so echo
 * cancellation is on) feeding both the Live stream (`realtimeInput.audio`, base64 —
 * the Live API's native input rate) and the recorder. Port of
 * <c>LiveVoice.startAudio/sendMic</c> — the original installs a 2400-frame tap on its
 * 24 kHz stream; we record at 16 kHz natively and read the same 2400 frames
 * (150 ms) per chunk.
 */
class MicStreamer(private val scope: CoroutineScope) : MicSource {

    /** Raw PCM16 chunks as they were spoken. First argument is shorts, second the count. */
    override var onChunk: ((ShortArray, Int) -> Unit)? = null

    private var record: AudioRecord? = null
    private var job: Job? = null

    override val active: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission") // LiveVoice asks for RECORD_AUDIO before starting
    override fun start() {
        if (job?.isActive == true) return
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // echo cancellation, so he doesn't hear himself
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, sampleRate * 2), // a quarter second of headroom
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("microphone unavailable")
        }
        record = audioRecord
        audioRecord.startRecording()

        job = scope.launch(Dispatchers.IO) {
            val frames = ShortArray(2400)
            val bytes = ByteBuffer.allocate(frames.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (true) {
                ensureActive()
                val read = audioRecord.read(frames, 0, frames.size)
                if (read <= 0) continue
                onChunk?.invoke(frames, read)

                bytes.clear()
                for (i in 0 until read) bytes.putShort(frames[i])
                val base64 = Base64.getEncoder()
                    .encodeToString(bytes.array().copyOf(read * 2))
                senders.forEach { it(base64) }
            }
        }
    }

    /** Where the realtime stream wants its audio (registered by LiveVoice). */
    private val senders = mutableListOf<(String) -> Unit>()

    override fun onAudioBase64(sender: (String) -> Unit) {
        senders.add(sender)
    }

    override fun stop() {
        job?.cancel()
        job = null
        record?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        record = null
    }
}

/**
 * Records the whole session's audio for proper transcription (meeting notes).
 * M2 stand-in: the tap points exist and are wired exactly like the original, but the
 * 300 s AAC chunking and the upload to the PC land with M5. Until then the samples
 * go nowhere.
 */
class ChunkRecorder {
    fun start(session: String) {
        currentSession = session
    }

    fun append(samples: ShortArray, count: Int) {
        recordedFrames += count // kept for an honest "is this thing on" log line in M5
    }

    fun finish() {
        currentSession = null
    }

    var currentSession: String? = null
        private set
    var recordedFrames: Long = 0
        private set
}
