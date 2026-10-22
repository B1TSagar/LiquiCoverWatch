package com.coverflow.wear

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.ambient.AmbientLifecycleObserver
import com.coverflow.wear.ui.BackWake
import com.coverflow.wear.ui.CoverFlowApp
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Bumped whenever the lyrics should open: the "Lyrics" launcher entry. */
    private val lyricsRequests = MutableStateFlow(0)

    /** Bumped whenever Now Playing should open: a tap on the LiquiWatch face's song (its date pill). */
    private val nowPlayingRequests = MutableStateFlow(0)

    /** Bumped on each two-finger tap (the app decides what it does on the page that's up). */
    private val twoFingerTaps = MutableStateFlow(0)

    private val ambient = AmbientState()

    // ---- System always-on (Wear OS ambient mode) --------------------------------------------

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambient.enter()
            }

            override fun onExitAmbient() {
                ambient.exit()
            }
        },
    )

    // ---- Our own always-on: screen kept on, dimmed when idle -------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private var keepOn = false
    private var dimmed = false
    private var lastInput = SystemClock.uptimeMillis()
    private var swallowKey = -1
    private var swallowTouch = false

    private val idleCheck = object : Runnable {
        override fun run() {
            if (keepOn && !dimmed && SystemClock.uptimeMillis() - lastInput >= Tuning.DIM_AFTER_MS) enterDim()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        ambient.keepOnListener = { on -> setKeepOn(on) }

        if (savedInstanceState == null && isLyricsLaunch(intent)) lyricsRequests.value += 1
        if (savedInstanceState == null && isNowPlayingLaunch(intent)) nowPlayingRequests.value += 1
        addOnNewIntentListener { newIntent ->
            if (isLyricsLaunch(newIntent)) lyricsRequests.value += 1
            if (isNowPlayingLaunch(newIntent)) nowPlayingRequests.value += 1
        }
        setContent {
            CoverFlowApp(
                onExit = { finish() },
                lyricsRequests = lyricsRequests,
                nowPlayingRequests = nowPlayingRequests,
                twoFingerTaps = twoFingerTaps,
                ambient = ambient,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        lastInput = SystemClock.uptimeMillis()
        handler.removeCallbacks(idleCheck)
        handler.post(idleCheck)
    }

    override fun onPause() {
        handler.removeCallbacks(idleCheck)
        if (dimmed) exitDim()
        super.onPause()
    }

    private fun setKeepOn(on: Boolean) {
        if (keepOn == on) return
        keepOn = on
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (dimmed) exitDim()
        }
        lastInput = SystemClock.uptimeMillis()
    }

    private fun enterDim() {
        dimmed = true
        // A fifth of the brightness as the brightness slider shows it (100 -> 20, 50 -> 10): the
        // slider isn't linear in the screen's light, so the level is scaled on the slider's scale.
        val slider = linearToSlider(currentBrightness())
        setBrightness(sliderToLinear(slider * Tuning.DIM_FRACTION).coerceIn(0.001f, 1f))
    }

    // Android's brightness slider curve (as in its settings: square root at the low end, then
    // logarithmic), between the screen's light (0..1) and the slider's position (0..1).
    private fun linearToSlider(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f) * 12f
        return (if (v <= 1f) 0.5f * sqrt(v) else 0.17883277f * ln(v - 0.28466892f) + 0.55991073f).coerceIn(0f, 1f)
    }

    private fun sliderToLinear(slider: Float): Float {
        val s = slider.coerceIn(0f, 1f)
        val v = if (s <= 0.5f) (s / 0.5f) * (s / 0.5f) else exp((s - 0.55991073f) / 0.17883277f) + 0.28466892f
        return (v / 12f).coerceIn(0f, 1f)
    }

    /** The screen's brightness right now (0..1): this window's own setting, else the watch's. */
    private fun currentBrightness(): Float {
        window.attributes.screenBrightness.takeIf { it >= 0f }?.let { return it }
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }.coerceIn(0f, 1f)
    }

    private fun exitDim() {
        dimmed = false
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        lastInput = SystemClock.uptimeMillis()
    }

    private fun setBrightness(value: Float) {
        val attrs = window.attributes
        attrs.screenBrightness = value
        window.attributes = attrs
    }

    // The first touch, bezel turn or button press only brightens the dimmed screen; it must not
    // also scroll covers, press buttons or count as a Back press.

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && dimmed) {
            exitDim()
            swallowTouch = true
        }
        if (swallowTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowTouch = false
            }
            return true
        }
        if (handleTwoFingerTap(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    // ---- Two-finger tap: switch covers on Cover Flow, lyrics elsewhere -------------------------

    private var twoFinger = false
    private var twoFingerStart = 0L
    private var twoFingerMoved = false
    private val twoFingerOrigins = HashMap<Int, Pair<Float, Float>>()
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop * 2f }

    /**
     * Two fingers down together and lifted without moving: on Cover Flow it switches between the
     * player's covers and the recently played songs, on any other page it opens the lyrics.
     * As soon as the second finger lands, whatever the first finger started (a tap on a cover,
     * a drag) is cancelled, and the rest of the gesture is kept from the screens.
     */
    private fun handleTwoFingerTap(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFinger = false
                twoFingerMoved = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> if (!twoFinger && ev.pointerCount == 2 &&
                ev.eventTime - ev.downTime <= Tuning.TWO_FINGER_TAP_MAX_GAP_MS
            ) {
                twoFinger = true
                twoFingerStart = ev.eventTime
                twoFingerOrigins.clear()
                for (i in 0 until ev.pointerCount) twoFingerOrigins[ev.getPointerId(i)] = ev.getX(i) to ev.getY(i)
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                return true
            }

            MotionEvent.ACTION_MOVE -> if (twoFinger) {
                for (i in 0 until ev.pointerCount) {
                    val origin = twoFingerOrigins[ev.getPointerId(i)] ?: continue
                    if (hypot(ev.getX(i) - origin.first, ev.getY(i) - origin.second) > touchSlop) twoFingerMoved = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (twoFinger) {
                val tapped = ev.actionMasked == MotionEvent.ACTION_UP && !twoFingerMoved &&
                    ev.eventTime - twoFingerStart <= Tuning.TWO_FINGER_TAP_MAX_MS
                twoFinger = false
                if (tapped) twoFingerTaps.value += 1
                return true
            }
        }
        return twoFinger
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (event.action == KeyEvent.ACTION_DOWN && dimmed) {
            exitDim()
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                // Wake, but let the press count: on its own it only wakes the screen, while a
                // double or triple press still does what it says (see MultiPressBackHandler).
                BackWake.mark()
                return super.dispatchKeyEvent(event)
            }
            swallowKey = event.keyCode
            return true
        }
        if (swallowKey == event.keyCode) {
            if (event.action == KeyEvent.ACTION_UP) swallowKey = -1
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (dimmed) {
            exitDim()
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    /**
     * The manifest's "Lyrics" entry is an alias of this activity, so the watch's Home-key
     * double-press shortcut (or the app list) can open straight into lyrics.
     */
    private fun isLyricsLaunch(intent: Intent?): Boolean =
        intent?.component?.className?.endsWith(".LyricsLauncher") == true

    /**
     * The manifest's "NowPlayingLauncher" is another alias of this activity, with no entry in the
     * app list: the LiquiWatch face's date pill opens it while a song plays, straight into Now
     * Playing.
     */
    private fun isNowPlayingLaunch(intent: Intent?): Boolean =
        intent?.component?.className?.endsWith(".NowPlayingLauncher") == true
}
