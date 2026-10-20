package dev.bluey.client.ui

import dev.bluey.client.link.FaceState
import dev.bluey.client.link.Mood
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Smooths what the PC asks for into lifelike motion: darting eyes, brows, blinks,
 * leaning, hops. Full port of <c>FaceAnimator</c> in iOS/Sources/FaceView.swift —
 * every spring constant and timing matches. Pure Kotlin (no framework deps) so the
 * motion itself is unit-testable.
 */
class FaceAnimator(private val random: Random = Random.Default) {

    /** A tiny damped spring, so every part of the face moves with a little life. */
    private class Spring(value: Double, val stiffness: Double, val damping: Double) {
        var velocity = 0.0

        var value = value
            private set

        fun step(target: Double, dt: Double) {
            val c = 2 * sqrt(stiffness) * damping
            velocity += (stiffness * (target - value) - c * velocity) * dt
            value += velocity * dt
        }
    }

    class Frame(
        val gazeX: Double,
        val gazeY: Double,
        val mood: Mood,
        val talk: Double,
        val closed: Double, // 0 open … 1 shut (blink)
        val breathe: Double,
        val time: Double,
        val pupil: Double, // pupil size multiplier
        val browLift: Double, // + raises the brows (in design px)
        val browTilt: Double, // + worried/curious, - focused (radians)
        val lean: Double, // head tilt toward where he's looking (radians)
        val hop: Double, // little idle hop (design px, up is +)
        val blush: Double, // 0…1
        val squint: Double, // 0…1 happy squint from below
    )

    private var target = FaceState.create()
    private var lastPacket = -100.0
    private val gazeX = Spring(0.0, 260.0, 0.82)
    private val gazeY = Spring(0.0, 260.0, 0.82)
    private val pupil = Spring(1.0, 160.0, 0.5)
    private val browLift = Spring(0.0, 180.0, 0.55)
    private val browTilt = Spring(0.0, 150.0, 0.6)
    private val lean = Spring(0.0, 60.0, 0.8)
    private val hop = Spring(0.0, 260.0, 0.35)
    private val blush = Spring(0.25, 40.0, 1.0)
    private val squint = Spring(0.0, 150.0, 0.7)
    private var talk = 0.0
    private var mood: Mood = Mood.LISTENING
    private var moodChanged = 0.0
    private var blinkStart = -1.0
    private var doubleBlink = false
    private var nextBlink = 2.0
    private var lastTime: Double? = null
    private var nextSaccade = 0.0
    private var wanderX = 0.0
    private var wanderY = -0.3
    private var jitterX = 0.0
    private var jitterY = 0.0
    private var nextJitter = 0.0
    private var nextHop = 6.0

    /** Set while a finger is on the screen, in -1…1 gaze units. (Vestigial in the original — kept, unwired.) */
    var touchGazeX: Double? = null
    var touchGazeY: Double? = null

    /** Mood set on the phone itself (waking up, talking). */
    var localMood: Mood? = null

    /** Loudness of the voice playing on this phone, 0…1. */
    var localTalk: () -> Double = { 0.0 }

    /** True while he's awake and talking with you. In follow mode his eyes stay locked on your mouse. */
    var awake = false

    fun receive(face: FaceState, time: Double) {
        target = face
        lastPacket = time
    }

    fun step(now: Double): Frame {
        val dt = min(now - (lastTime ?: now), 1.0 / 20)
        lastTime = now

        val live = now - lastPacket < 2.5
        val wantedMood = localMood ?: if (live) target.mood else Mood.LISTENING
        if (wantedMood != mood) {
            mood = wantedMood
            moodChanged = now
            blinkStart = now // blink through every mood change
            if (mood != Mood.SLEEPY && mood != Mood.RESTING) {
                hop.velocity += 260.0 // and a little bounce of surprise
                pupil.velocity += 3.0
            }
        }

        // Where to look, plus tiny darting movements so the eyes never sit dead still.
        var wantX: Double
        var wantY: Double
        val touchX = touchGazeX
        val touchY = touchGazeY
        if (touchX != null && touchY != null) {
            wantX = touchX; wantY = touchY
        } else if (live) {
            wantX = target.gazeX; wantY = target.gazeY
        } else {
            if (now > nextSaccade) {
                wanderX = random.nextDouble(-0.9, 0.9)
                wanderY = random.nextDouble(-0.9, 0.4)
                nextSaccade = now + random.nextDouble(0.6, 2.2)
            }
            wantX = wanderX; wantY = wanderY
        }
        if (mood == Mood.THINKING) {
            wantX = 0.6 + 0.08 * sin(now * 1.3)
            wantY = -0.85
        }
        if (now > nextJitter) {
            // While he's listening his eyes stay on your mouse; the livelier darting is for replying.
            val amount = when {
                !awake -> 0.015
                mood == Mood.TALKING -> 0.07
                mood == Mood.THINKING -> 0.05
                else -> 0.02
            }
            jitterX = random.nextDouble(-amount, amount)
            jitterY = random.nextDouble(-amount, amount)
            nextJitter = now + random.nextDouble(0.5, 1.4)
        }
        gazeX.step(wantX + jitterX, dt)
        gazeY.step(wantY + jitterY, dt)

        var wantTalk = if (live) target.talk
        else if (localMood == Mood.TALKING) 0.5 + 0.5 * sin(now * 19) * sin(now * 7.3) else 0.0
        wantTalk = max(wantTalk, localTalk())
        talk += (wantTalk - talk) * min(1.0, dt * 25)

        // Expression targets per mood.
        var wantPupil = 1.0
        var wantLift = 0.0
        var wantTilt = 0.0
        var wantBlush = 0.25
        var wantSquint = 0.0
        when (mood) {
            Mood.LISTENING -> {
                wantPupil = 1.12; wantLift = 10.0
            }
            Mood.TALKING -> {
                wantPupil = 1.05; wantLift = 6 + talk * 18; wantTilt = 0.05 * sin(now * 2.3)
            }
            Mood.POINTING -> {
                wantPupil = 0.95; wantLift = -4.0; wantTilt = -0.14
            }
            Mood.THINKING -> {
                wantPupil = 0.9; wantLift = 4.0; wantTilt = 0.22
            }
            Mood.HAPPY -> {
                wantPupil = 1.2; wantLift = 16.0; wantBlush = 0.85; wantSquint = 1.0
            }
            Mood.RESTING -> {
                wantPupil = 0.9; wantLift = -8.0; wantBlush = 0.15
            }
            Mood.SLEEPY -> {
                wantPupil = 0.88; wantLift = -10.0; wantTilt = 0.12; wantBlush = 0.15
            }
        }
        pupil.step(wantPupil, dt)
        browLift.step(wantLift, dt)
        browTilt.step(wantTilt, dt)
        blush.step(wantBlush, dt)
        squint.step(wantSquint, dt)
        lean.step(-gazeX.value * 0.045, dt)

        // An occasional happy little hop when nothing much is going on.
        if (now > nextHop) {
            if (talk < 0.05 && mood != Mood.SLEEPY && mood != Mood.RESTING) {
                hop.velocity += random.nextDouble(180.0, 320.0)
            }
            nextHop = now + random.nextDouble(7.0, 14.0)
        }
        hop.step(0.0, dt)

        // Blinks, sometimes doubled. When he's drowsy they're slow and heavy.
        val drowsy = mood == Mood.SLEEPY
        if (now > nextBlink) {
            blinkStart = now
            doubleBlink = !drowsy && random.nextDouble() < 0.25
            nextBlink = now + if (drowsy) random.nextDouble(1.6, 3.0) else random.nextDouble(2.0, 5.0)
        }
        val p = (now - blinkStart) / (if (drowsy) 0.7 else 0.15)
        var closed = if (p in 0.0..1.0) sin(PI * p) else 0.0
        if (doubleBlink && p in 1.3..2.3) closed = sin(PI * (p - 1.3))

        val breathe = sin(now * (if (mood == Mood.RESTING) 1.1 else 1.8))
        return Frame(
            gazeX = gazeX.value, gazeY = gazeY.value, mood = mood, talk = talk, closed = closed,
            breathe = breathe, time = now, pupil = pupil.value, browLift = browLift.value,
            browTilt = browTilt.value, lean = lean.value, hop = hop.value, blush = blush.value,
            squint = squint.value.coerceIn(0.0, 1.0),
        )
    }

    companion object {
        /** Focused gaze target while thinking — exposed for tests. */
        fun thinkingGaze(now: Double) = 0.6 + 0.08 * sin(now * 1.3)
    }
}
