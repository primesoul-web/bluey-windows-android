package dev.bluey.client.ui

import dev.bluey.client.link.FaceState
import dev.bluey.client.link.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** The face motion math: springs, blinks, mood expressions — the port of FaceAnimator. */
class FaceAnimatorTest {

    private fun animator(seed: Int = 7) = FaceAnimator(Random(seed))

    private fun settle(a: FaceAnimator, from: Double, to: Double, step: Double = 1.0 / 60): FaceAnimator.Frame {
        var frame = a.step(from)
        var t = from
        while (t < to) {
            frame = a.step(t)
            t += step
        }
        return frame
    }

    @Test
    fun `gaze springs toward the PC's target`() {
        val a = animator()
        a.receive(FaceState.create(gazeX = -1.0, gazeY = -0.8), 0.0)
        val frame = settle(a, 0.0, 2.0)
        assertTrue(frame.gazeX < -0.9)
        assertTrue(frame.gazeY < -0.7)
    }

    @Test
    fun `a mood change blinks`() {
        val a = animator()
        a.localMood = Mood.HAPPY
        val frame = a.step(0.0)
        a.step(0.03)
        val mid = a.step(0.05)
        assertTrue("closed was ${mid.closed}", mid.closed > 0.05)
        // And the blink is over a little later.
        val after = settle(a, 0.05, 0.5)
        assertEquals(0.0, after.closed, 1e-9)
    }

    @Test
    fun `an exciting mood change kicks a hop and a pupil pop`() {
        val calm = animator()
        calm.localMood = Mood.HAPPY
        calm.step(0.0)
        val frame = calm.step(0.05)
        assertTrue("hop was ${frame.hop}", abs(frame.hop) > 1.0)
        assertTrue("pupil was ${frame.pupil}", frame.pupil > 1.0) // the +3 velocity kick

        val drowsy = animator()
        drowsy.localMood = Mood.SLEEPY
        drowsy.step(0.0)
        val sleepy = drowsy.step(0.05)
        assertEquals(0.0, sleepy.hop, 1e-9) // no bounce of surprise when getting drowsy
    }

    @Test
    fun `thinking pins the gaze up and to the right`() {
        val a = animator()
        a.localMood = Mood.THINKING
        a.awake = true
        val frame = settle(a, 0.0, 1.5)
        assertEquals(FaceAnimator.thinkingGaze(frame.time), frame.gazeX, 0.08)
        assertEquals(-0.85, frame.gazeY, 0.08)
    }

    @Test
    fun `no packets for a while wanders the eyes (saccades)`() {
        val a = animator(1)
        a.step(0.0)
        val early = settle(a, 0.0, 3.0)
        val late = settle(a, 3.0, 6.0)
        // Two windows past the 2.5 s liveness window: the wander targets differ.
        assertTrue(early.gazeX != late.gazeX || early.gazeY != late.gazeY)
    }

    @Test
    fun `local talk drives the talking bounce`() {
        val a = animator()
        a.localTalk = { 1.0 }
        a.step(0.0)
        val frame = settle(a, 0.0, 0.5)
        assertTrue("talk was ${frame.talk}", frame.talk > 0.9)
    }

    @Test
    fun `breathing is the exact sine`() {
        val a = animator()
        a.step(1.0)
        val frame = a.step(1.05)
        assertEquals(kotlin.math.sin(1.8 * 1.05), frame.breathe, 1e-9)
    }

    @Test
    fun `happy squints and blushes`() {
        val a = animator()
        a.localMood = Mood.HAPPY
        a.step(0.0)
        val frame = settle(a, 0.0, 1.0)
        assertTrue(frame.squint > 0.8) // heading for the full happy squint
        assertTrue(frame.blush > 0.5) // cheeks rosier than the 0.25 baseline
        assertEquals(Mood.HAPPY, frame.mood)
    }

    @Test
    fun `happy talking keeps the mouth open instead of the smile`() {
        val a = animator()
        a.localMood = Mood.HAPPY
        a.localTalk = { 0.8 }
        a.step(0.0)
        val frame = settle(a, 0.0, 0.5)
        assertTrue(frame.talk > 0.5) // talk > 0.1 means nose-open, not the smile path
    }

    @Test
    fun `the local mood beats the packet mood, packets beat the default`() {
        val a = animator()
        a.receive(FaceState.create(mood = Mood.POINTING), 0.0)
        assertEquals(Mood.POINTING, a.step(0.0).mood) // live packet wins over listening

        a.localMood = Mood.THINKING
        assertEquals(Mood.THINKING, a.step(0.05).mood) // local wins while set

        a.localMood = null
        assertEquals(Mood.POINTING, a.step(0.1).mood) // back to the packet's mood
    }

    @Test
    fun `stale packets fall back to listening`() {
        val a = animator()
        a.receive(FaceState.create(mood = Mood.POINTING), 0.0)
        a.step(0.0)
        val frame = settle(a, 0.0, 3.0) // way past the 2.5 s liveness window
        assertEquals(Mood.LISTENING, frame.mood)
    }
}
