package dev.bluey.client.ui

import dev.bluey.client.realtime.LiveVoice

/** Should a single tap on his face stop the reply? — one pure decision, kept out of
 *  the gesture handlers so it can be tested without Compose.
 *
 *  Why this exists (v0.3.9 → v0.3.10): the tap-to-stop handler uses
 *  detectTapGestures(onTap, onDoubleTap) with no onLongPress, so Compose counts ANY
 *  press-and-release as a tap, however long it was held. The finger-up that ends a
 *  hold-to-ask therefore arrives as a phantom onTap about 300 ms after release (the
 *  double-tap window) — by then the question has gone out, the state is Thinking, and
 *  the phantom tap cancelled every ask the moment it was asked. holdToAsk() does not
 *  consume the pointer events, which is why both handlers see them.
 *
 *  The fix: taps that arrive within [HOLD_ECHO_MS] of a hold are treated as that
 *  hold's echo and ignored. The accepted tradeoff: a deliberate tap-to-stop in the
 *  first 1.5 s after releasing a hold is also swallowed — replies almost never
 *  become stoppable that fast, and the phantom-tap bug it prevents was far worse. */
object TapToStop {

    /** How long after a hold-to-ask ends that a tap is still treated as its echo. */
    const val HOLD_ECHO_MS = 1_500L

    /** [lastHoldMs] is 0 until the first hold (the stamp holder's initial value). */
    fun shouldCancel(
        nowMs: Long,
        lastHoldMs: Long,
        state: LiveVoice.State,
        windowMs: Long = HOLD_ECHO_MS,
    ): Boolean {
        if (lastHoldMs > 0 && nowMs - lastHoldMs < windowMs) return false // the hold's echo
        return state == LiveVoice.State.Thinking || state == LiveVoice.State.Speaking
    }
}
