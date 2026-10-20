package dev.bluey.client.realtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Plays the chirp and reports how loud it is right now — the port of the original's
 * AVAudioPlayerNode tap (which fed `level`, the talk-bounce driver). Writes float
 * chunks to an [AudioTrack] at playback pace, so the running RMS of what is audible
 * is exactly what the face reads.
 */
class ChirpPlayer(private val scope: CoroutineScope) : ChirpSink {

    /** Loudness of the chirp playing right now, 0…1 (the face's localTalk). */
    override var level: Double = 0.0
        private set

    /** Number of buffers still queued/playing; the original's pendingBuffers. */
    override var pendingBuffers: Int = 0
        private set

    private var track: AudioTrack? = null
    private var jobs = mutableListOf<Job>()

    /** Plays a synthesized chirp at the given volume; [onDone] fires when it finishes. */
    override fun play(samples: FloatArray, volume: Float, onDone: () -> Unit) {
        pendingBuffers += 1
        val job = scope.launch(Dispatchers.IO) {
            try {
                val audioTrack = obtainTrack()
                audioTrack.setVolume(volume.coerceIn(0f, 1f))
                // Write in small chunks at playback pace, reading the live level off each.
                var written = 0
                val chunk = 480 // 20 ms
                while (written < samples.size) {
                    ensureActive()
                    val n = min(chunk, samples.size - written)
                    val piece = samples.copyOfRange(written, written + n)
                    audioTrack.write(piece, 0, n, AudioTrack.WRITE_BLOCKING)
                    level = min(1.0, rms(piece) * 5)
                    written += n
                }
                // Give the tail of the buffer time to actually leave the speaker.
                val head = audioTrack.playbackHeadPosition
                while (audioTrack.playbackHeadPosition < head + (ChirpSynth.SAMPLE_RATE * 0.05).toInt()) {
                    ensureActive()
                    Thread.sleep(10)
                }
            } catch (_: Exception) {
                // A chirp that can't play is sad but never fatal.
            } finally {
                if (level > 0) level = 0.0
                pendingBuffers -= 1
                withContext(Dispatchers.Main.immediate) { onDone() }
            }
        }
        jobs.add(job)
    }

    /** Stops everything at once (sleep). */
    override fun stopAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        pendingBuffers = 0
        level = 0.0
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun obtainTrack(): AudioTrack {
        val existing = track
        if (existing != null) return existing
        val fresh = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(ChirpSynth.SAMPLE_RATE.toInt())
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    ChirpSynth.SAMPLE_RATE.toInt(), AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 2,
            )
            .build()
        fresh.play()
        track = fresh
        return fresh
    }

    private fun rms(samples: FloatArray): Double {
        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        return sqrt(sum / samples.size.coerceAtLeast(1))
    }
}
