package com.coverflow.wear.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.coverflow.wear.Tuning
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Turns bezel (or crown) rotation into whole steps: one detent on a Galaxy Watch bezel = one step.
 * Positive steps = clockwise when [Tuning.CLOCKWISE_IS_NEXT] is true.
 *
 * The node grabs focus while [active], because rotary events go to the focused node.
 */
@Composable
fun Modifier.rotarySteps(active: Boolean, onStep: (Int) -> Unit): Modifier {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val latestOnStep by rememberUpdatedState(onStep)
    val latestActive by rememberUpdatedState(active)
    // One bezel detent arrives as one AXIS_SCROLL unit, i.e. scaledVerticalScrollFactor pixels.
    val stepPx = remember(context) {
        ViewConfiguration.get(context).scaledVerticalScrollFactor * 0.8f * Tuning.DETENTS_PER_STEP
    }
    val accumulated = remember { FloatArray(1) }

    LaunchedEffect(active) {
        if (active) {
            delay(32) // let the screen finish attaching before taking focus
            runCatching { focusRequester.requestFocus() }
        }
    }
    // Coming back from another app or the watch face can drop focus; take it back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (latestActive) runCatching { focusRequester.requestFocus() }
    }

    return this
        .onRotaryScrollEvent { event ->
            if (!latestActive) return@onRotaryScrollEvent false
            val px = if (Tuning.CLOCKWISE_IS_NEXT) event.verticalScrollPixels else -event.verticalScrollPixels
            accumulated[0] += px
            var steps = 0
            while (accumulated[0] >= stepPx) {
                accumulated[0] -= stepPx
                steps++
            }
            while (accumulated[0] <= -stepPx) {
                accumulated[0] += stepPx
                steps--
            }
            if (steps != 0) latestOnStep(steps)
            true
        }
        .focusRequester(focusRequester)
        .focusable()
}

/** Spring state kept across target changes: velocity, stiffness and the recent steps. */
private class FlowMotion {
    var velocity = 0f
    var omega = Tuning.FLOW_RESPONSIVENESS
    private var lastTarget: Int? = null
    private val stepTimes = ArrayDeque<Long>()
    private val stepSizes = ArrayDeque<Int>()

    fun record(target: Int, nowNanos: Long) {
        val previous = lastTarget
        lastTarget = target
        val size = if (previous == null) 0 else abs(target - previous)
        // Jumps of several covers at once are the app (e.g. following a new song), not the
        // bezel, so they don't count as turning it fast.
        if (size in 1..MAX_STEP) {
            stepTimes.addLast(nowNanos)
            stepSizes.addLast(size)
        }
    }

    /**
     * How fast the target has been moving lately, in items per second: the steps since the
     * oldest click in the window, over the time since it. A single click (slow turning) is no
     * speed at all, so it gets the relaxed glide.
     */
    fun speed(nowNanos: Long): Float {
        val window = Tuning.FLOW_SPEED_WINDOW_MS * 1_000_000L
        while (stepTimes.isNotEmpty() && nowNanos - stepTimes.first() > window) {
            stepTimes.removeFirst()
            stepSizes.removeFirst()
        }
        if (stepTimes.size < 2) return 0f
        var steps = 0
        for (i in 1 until stepSizes.size) steps += stepSizes[i]
        val elapsed = (nowNanos - stepTimes.first()).coerceAtLeast(8_000_000L) / 1_000_000_000f
        return steps / elapsed
    }

    /**
     * The stiffness that keeps the covers on the song the text names. A critically damped
     * spring following a steady speed v trails it by 2v/w items, so w = 2v / FLOW_MAX_LAG keeps
     * the lag within a quarter of a cover: the centre cover is always the one the text names.
     */
    fun wantedOmega(nowNanos: Long): Float =
        (2f * speed(nowNanos) / Tuning.FLOW_MAX_LAG)
            .coerceIn(Tuning.FLOW_RESPONSIVENESS, Tuning.FLOW_MAX_RESPONSIVENESS)

    companion object {
        const val MAX_STEP = 4
    }
}

/**
 * A smoothly moving position that chases [target] with a critically damped spring.
 *
 * Unlike restarting an animation on every bezel click, this keeps its velocity when the target
 * moves again, never snaps, and never overshoots. Its speed follows the bezel: turned slowly,
 * covers glide one by one; turned fast, the spring stiffens so the covers change exactly as
 * fast as the song title does, and stop when the bezel stops instead of coasting on. Runs
 * frames only while moving.
 */
@Composable
fun rememberFlowPosition(target: Int): State<Float> {
    val position = remember { mutableFloatStateOf(target.toFloat()) }
    val motion = remember { FlowMotion() }
    val latestTarget by rememberUpdatedState(target)

    LaunchedEffect(target) {
        motion.record(target, System.nanoTime()) // same clock as frame times
        var last = 0L
        while (true) {
            val settled = withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                last = now
                val goal = latestTarget.toFloat()

                // Stiffen at once when the bezel speeds up; relax gradually when it slows, as
                // dropping the stiffness suddenly mid-move could make the covers overshoot.
                val wanted = motion.wantedOmega(now)
                motion.omega = if (wanted >= motion.omega) {
                    wanted
                } else {
                    wanted + (motion.omega - wanted) * exp(-dt * 1000f / Tuning.FLOW_RELAX_MS)
                }
                val w = motion.omega

                // Exact solution of a critically damped spring over dt. A step-by-step (Euler)
                // version rings back and forth when frames are slow, which made the cover
                // shiver after the bezel stopped; this can't, whatever the frame time.
                val e0 = position.floatValue - goal
                val v0 = motion.velocity
                val c = v0 + w * e0
                val decay = exp(-w * dt)
                val e1 = (e0 + c * dt) * decay
                val v1 = (v0 - w * c * dt) * decay
                if (abs(e1) < 0.001f && abs(v1) < 0.01f) {
                    position.floatValue = goal
                    motion.velocity = 0f
                    motion.omega = wanted // at rest, so it can relax fully without any overshoot
                    true
                } else {
                    position.floatValue = goal + e1
                    motion.velocity = v1
                    false
                }
            }
            if (settled) break
        }
    }
    return position
}

/**
 * Ticks every time the moving [position] passes a whole item: one tick per cover (or row) that
 * crosses the center, whether it moved by bezel, tap or swipe.
 * [userDriven] lets programmatic jumps (e.g. following a new song) stay silent.
 */
@Composable
fun TickOnItemChange(position: State<Float>, haptics: Haptics, userDriven: () -> Boolean) {
    val latestUserDriven by rememberUpdatedState(userDriven)
    LaunchedEffect(position, haptics) {
        snapshotFlow { position.value.roundToInt() }
            .distinctUntilChanged()
            .drop(1)
            .collect { if (latestUserDriven()) haptics.tick() }
    }
}

private class PressCounter {
    var count = 0
    var job: Job? = null

    /** The first press of this run woke the dimmed screen. */
    var woke = false
}

/**
 * Marks a Back press that woke the dimmed screen (the activity sees the key first). On its own
 * that press only wakes the screen; as the first of a double or triple press it counts.
 */
object BackWake {
    @Volatile
    private var at = 0L

    fun mark() {
        at = SystemClock.uptimeMillis()
    }

    /** Whether the press being handled now is the one that woke the screen. */
    fun justWoke(): Boolean = SystemClock.uptimeMillis() - at < 1_000L
}

/**
 * The Galaxy Watch Back key with 1 to 4 presses. Calls [onPresses] with the count once the
 * presses stop for [Tuning.MULTI_PRESS_WINDOW_MS]; a fourth press fires immediately. A single
 * press that only woke the dimmed screen is dropped.
 */
@Composable
fun MultiPressBackHandler(onPresses: (Int) -> Unit) {
    val latest by rememberUpdatedState(onPresses)
    val scope = rememberCoroutineScope()
    val counter = remember { PressCounter() }

    BackHandler(enabled = true) {
        counter.job?.cancel()
        if (counter.count == 0) counter.woke = BackWake.justWoke()
        counter.count++
        if (counter.count >= 4) {
            counter.count = 0
            counter.job = null
            latest(4)
        } else {
            counter.job = scope.launch {
                delay(Tuning.MULTI_PRESS_WINDOW_MS)
                val presses = counter.count
                counter.count = 0
                counter.job = null
                if (presses == 1 && counter.woke) return@launch // it only woke the screen
                if (presses > 0) latest(presses)
            }
        }
    }
}

/** Short vibrations for cover changes, bezel detents and button presses. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = run {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
        v?.takeIf { it.hasVibrator() }
    }
    private val amplitude = vibrator?.hasAmplitudeControl() == true

    private fun shot(ms: Long, strength: Int): VibrationEffect =
        VibrationEffect.createOneShot(ms, if (amplitude) strength.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE)

    private val tickEffect = shot(Tuning.TICK_MS, Tuning.TICK_STRENGTH)
    private val clickEffect = shot(32L, 220)
    private val heavyEffect = shot(48L, 255)
    private val doubleEffect = VibrationEffect.createWaveform(longArrayOf(0L, 24L, 70L, 24L), -1)
    private var lastTick = 0L
    private var lastEdge = 0L

    private fun play(effect: VibrationEffect) {
        if (!Tuning.HAPTICS) return
        try {
            vibrator?.vibrate(effect)
        } catch (e: Exception) {
            // Vibration is best-effort.
        }
    }

    /** A cover / row / bezel detent moved. Merges ticks that come faster than the motor can render. */
    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < Tuning.MIN_TICK_GAP_MS) return
        lastTick = now
        play(tickEffect)
    }

    /** Selected / pressed something. */
    fun confirm() = play(clickEffect)

    /** Went back a level or cancelled. */
    fun back() = play(doubleEffect)

    /** Mode change (entering or confirming a scrub, jumping to Now Playing). */
    fun strong() = play(heavyEffect)

    /** Bezel turned past the first/last item (rate-limited so a fast spin doesn't buzz). */
    fun edge() {
        val now = SystemClock.uptimeMillis()
        if (abs(now - lastEdge) > 450) {
            lastEdge = now
            play(heavyEffect)
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val context = LocalContext.current
    return remember(context) { Haptics(context) }
}
