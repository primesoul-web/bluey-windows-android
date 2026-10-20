package dev.bluey.client.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** The chirp: exact constants from LiveVoice.swift, checked sample by sample. */
class ChirpSynthTest {

    @Test
    fun `same seed synthesizes the same chirp`() {
        val a = ChirpSynth.synthesize(3, Random(42))
        val b = ChirpSynth.synthesize(3, Random(42))
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `two syllables land in the plausible duration window`() {
        // One 0.07–0.09 s note + one 0.13 s wobbling tail + 2 × 28 ms gaps.
        val minSeconds = 0.07 + 0.13 + 0.056
        val maxSeconds = 0.09 + 0.13 + 0.057
        repeat(20) {
            val samples = ChirpSynth.synthesize(2, Random(it))
            val seconds = samples.size / ChirpSynth.SAMPLE_RATE
            assertTrue("was $seconds s", seconds >= minSeconds && seconds <= maxSeconds)
        }
    }

    @Test
    fun `amplitude never exceeds the peak times the wave bound of 112 percent`() {
        repeat(30) {
            val samples = ChirpSynth.synthesize(5, Random(it))
            val peak = max(abs(samples.min()), abs(samples.max()))
            assertTrue("peak was $peak", peak <= ChirpSynth.AMPLITUDE * 1.12 + 1e-6)
            assertTrue("chirp must actually be audible, peak was $peak", peak > ChirpSynth.AMPLITUDE * 0.3)
        }
    }

    @Test
    fun `ends with exactly one 28 ms silence gap`() {
        val samples = ChirpSynth.synthesize(4, Random(7))
        val gap = (ChirpSynth.SAMPLE_RATE * ChirpSynth.GAP_SECONDS).toInt()
        assertEquals(672, gap)
        assertTrue(samples.takeLast(gap).all { it == 0f })
        // …and the sample just before the final gap is part of the wobbling tail.
        assertTrue(samples[samples.size - gap - 1] != 0f)
    }

    @Test
    fun `more syllables means more sound`() {
        val two = ChirpSynth.synthesize(2, Random(1)).size
        val five = ChirpSynth.synthesize(5, Random(1)).size
        assertTrue(five > two)
    }

    @Test
    fun `wake chirp is the two-syllable one`() {
        // LiveVoice chirps 2 on connect and 3–5 (word-count scaled) on replies; the
        // constants live here so the test pins the relationship.
        val wake = ChirpSynth.synthesize(2, Random(3)).size
        val reply = ChirpSynth.synthesize("one two three four five".split(" ").size.coerceIn(3, 5), Random(3)).size
        assertTrue(reply > wake)
    }
}
