package dev.bluey.client.ui

import dev.bluey.client.realtime.LiveVoice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tap-to-stop decision, pulled out of the gesture handlers (v0.3.10).
 *  The case that matters most is the first one: the phantom onTap that Compose
 *  delivers ~300 ms after the finger-up of a hold-to-ask. */
class TapToStopTest {

    @Test
    fun `tap right after a hold is ignored`() {
        // Finger up at t=60_000; the phantom tap lands ~300 ms later, state Thinking.
        assertFalse(TapToStop.shouldCancel(60_300, 60_000, LiveVoice.State.Thinking))
    }

    @Test
    fun `tap right after a hold is ignored even while speaking`() {
        assertFalse(TapToStop.shouldCancel(60_300, 60_000, LiveVoice.State.Speaking))
    }

    @Test
    fun `tap after more than the window still cancels while thinking`() {
        assertTrue(TapToStop.shouldCancel(61_600, 60_000, LiveVoice.State.Thinking))
    }

    @Test
    fun `tap after more than the window still cancels while speaking`() {
        assertTrue(TapToStop.shouldCancel(61_501, 60_000, LiveVoice.State.Speaking))
    }

    @Test
    fun `window boundary is exclusive`() {
        // Exactly 1500 ms after the hold the echo has faded; 1 ms earlier it hasn't.
        assertTrue(TapToStop.shouldCancel(61_500, 60_000, LiveVoice.State.Thinking))
        assertFalse(TapToStop.shouldCancel(61_499, 60_000, LiveVoice.State.Thinking))
    }

    @Test
    fun `quick tap with no hold cancels while thinking`() {
        // lastHoldMs = 0: no hold has ever happened — nothing to echo.
        assertTrue(TapToStop.shouldCancel(5_000, 0, LiveVoice.State.Thinking))
    }

    @Test
    fun `quick tap with no hold cancels while speaking`() {
        assertTrue(TapToStop.shouldCancel(5_000, 0, LiveVoice.State.Speaking))
    }

    @Test
    fun `no cancel while listening`() {
        assertFalse(TapToStop.shouldCancel(5_000, 0, LiveVoice.State.Listening))
        // Even fresh off a hold: if he's merely listening, a tap is not a stop.
        assertFalse(TapToStop.shouldCancel(60_300, 60_000, LiveVoice.State.Listening))
    }

    @Test
    fun `no cancel while asleep, waking or asking`() {
        assertFalse(TapToStop.shouldCancel(5_000, 0, LiveVoice.State.Asleep))
        assertFalse(TapToStop.shouldCancel(5_000, 0, LiveVoice.State.Waking))
        // Asking = finger still down mid-hold (the stamp is fresh by definition).
        assertFalse(TapToStop.shouldCancel(60_300, 60_000, LiveVoice.State.Asking))
    }

    @Test
    fun `the echo window covers the double-tap window`() {
        // Compose holds a single tap back until the double-tap window closes
        // (~300 ms), so the phantom can land up to ~600 ms after release with
        // slow devices factored in — still comfortably inside the 1500 ms echo.
        assertFalse(TapToStop.shouldCancel(60_600, 60_000, LiveVoice.State.Thinking))
    }

    @Test
    fun `stamp at hold start also suppresses`() {
        // The stamp is written at hold start AND release; either way the tap
        // that follows within the window is an echo.
        assertFalse(TapToStop.shouldCancel(60_000, 60_000, LiveVoice.State.Thinking))
    }
}
