package dev.bluey.client.realtime

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A tiny cartoon chirp: a few soft, round, bell-like notes from a happy pentatonic
 * scale, each with a little upward "boop" at the start, like a small creature humming.
 * Pure port of <c>LiveVoice.chirp(syllables:)</c> — every constant matches.
 */
object ChirpSynth {

    const val SAMPLE_RATE = 24_000.0

    /** C major pentatonic, two octaves up high (C6…E7), so it always sounds sweet together. */
    private val scale = doubleArrayOf(1046.5, 1174.7, 1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0)

    /** The random walk between notes. */
    private val steps = intArrayOf(-1, 1, 1, 2)

    /** Peak amplitude of the synthesized wave (sinφ + 0.12·sin2φ means |wave| ≤ 1.12). */
    const val AMPLITUDE = 0.26

    /** The gap of silence between syllables, in seconds. */
    const val GAP_SECONDS = 0.028

    fun synthesize(syllables: Int, random: Random = Random.Default): FloatArray {
        val rate = SAMPLE_RATE
        var index = random.nextInt(1, 4) // 1…3, like Int.random(in: 1...3)
        val samples = ArrayList<Float>(syllables * 4096)
        for (i in 0 until syllables) {
            if (i > 0) {
                index = (index + steps.random(random)).coerceIn(0, scale.size - 1)
            }
            val note = scale[index] * 0.5 // drop an octave: rounder, less piercing
            val duration = if (i == syllables - 1) 0.13 else random.nextDouble(0.07, 0.09)
            val count = (duration * rate).toInt()
            var phase = 0.0
            for (n in 0 until count) {
                val time = n / rate
                val t = n.toDouble() / count
                // Scoops up into the note over the first 25 ms, with a gentle wobble on the last one.
                val scoop = 1 - 0.18 * exp(-time / 0.012)
                val wobble = if (i == syllables - 1) 1 + 0.012 * sin(time * 2 * PI * 18) else 1.0
                phase += 2 * PI * note * scoop * wobble / rate
                val attack = min(1.0, time / 0.006)
                val envelope = attack * exp(-t * 3.2) * (1 - t.pow(6))
                val wave = sin(phase) + 0.12 * sin(2 * phase)
                samples.add((wave * envelope * AMPLITUDE).toFloat())
            }
            repeat((rate * GAP_SECONDS).toInt()) { samples.add(0f) }
        }
        return samples.toFloatArray()
    }
}
