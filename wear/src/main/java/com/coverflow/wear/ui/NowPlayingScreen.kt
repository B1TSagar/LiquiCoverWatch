package com.coverflow.wear.ui

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.coverflow.shared.PlayerState
import com.coverflow.shared.Track
import com.coverflow.wear.Connection
import com.coverflow.wear.CoverColors
import com.coverflow.wear.Tuning
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Now Playing: big cover, song/artist/album, and liquid glass all round: the progress is a
 * slider round the edge, a thin track in a tone of the album's colour up to a clear glass knob
 * that bends it, the transport controls are separate drops, and using one (a tap, or Back for
 * play/pause) makes it swell and spill into the buttons next to it through clear glass necks
 * that cover them, its colour filling most of each, then drain back apart. Turning
 * the bezel shows the volume as a curved pill of frosted glass along the right edge, filled
 * with the same colour up to a clear glass knob, just inside the progress tube.
 *
 * Back: 1 = play/pause, 2 = back, 3 = scrub mode. Bezel = phone volume (follows the arc).
 *
 * Scrub mode: the bezel moves a target time along the ring while the song keeps playing
 * untouched. Back 2 = jump to the target, Back 1 (or 3) = cancel.
 */
@Composable
fun NowPlayingScreen(
    state: PlayerState?,
    art: Map<String, ImageBitmap>,
    active: Boolean,
    actions: ScreenActions,
    haptics: Haptics,
    requestArt: (String) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onVolume: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onLyrics: () -> Unit,
    onRate: () -> Unit,
    starting: String? = null,
    startingTrack: Track? = null,
    artColors: Map<String, Color> = emptyMap(),
) {
    val current = state?.current
    val latestState by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()

    LaunchedEffect(current?.artKey) { current?.artKey?.let(requestArt) }

    // ---- Scrub mode ---------------------------------------------------------------------------
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableLongStateOf(0L) }

    // A new song makes the pending target meaningless.
    LaunchedEffect(current?.id) { scrubbing = false }

    fun startScrub() {
        val s = latestState ?: return
        if (s.current == null) return
        scrubTarget = s.positionAt(System.currentTimeMillis())
        scrubbing = true
        haptics.strong()
    }

    fun cancelScrub() {
        scrubbing = false
        haptics.back()
    }

    fun commitScrub() {
        scrubbing = false
        onSeek(scrubTarget)
        haptics.strong()
    }

    fun scrubBy(steps: Int) {
        val duration = latestState?.current?.durationMs ?: 0L
        val step = if (duration > 0) {
            (duration / Tuning.SCRUB_CLICKS_PER_TRACK).coerceIn(Tuning.SCRUB_MIN_STEP_MS, Tuning.SCRUB_MAX_STEP_MS)
        } else Tuning.SCRUB_STEP_UNKNOWN_LENGTH_MS
        val raw = scrubTarget + steps * step
        val target = if (duration > 0) raw.coerceIn(0L, duration) else raw.coerceAtLeast(0L)
        if (target == scrubTarget) {
            haptics.edge()
        } else {
            scrubTarget = target
            haptics.tick()
        }
    }

    // ---- Volume -------------------------------------------------------------------------------
    var volumeTouchedAt by remember { mutableLongStateOf(0L) }
    var showVolume by remember { mutableStateOf(false) }
    LaunchedEffect(volumeTouchedAt) {
        if (volumeTouchedAt > 0L) {
            showVolume = true
            delay(1_400)
            showVolume = false
        }
    }
    val volumeAlpha by animateFloatAsState(if (showVolume && !scrubbing) 1f else 0f, glassSpring(), label = "volume")
    // The liquid in the volume arc flows to its new level.
    val volumeLevel = latestState?.let { if (it.maxVolume > 0) it.volume.toFloat() / it.maxVolume else 0f } ?: 0f
    val volumeAnim = remember { Animatable(volumeLevel) }
    LaunchedEffect(volumeLevel) { volumeAnim.animateTo(volumeLevel, glassSpring()) }

    fun volume(bezelSteps: Int) {
        val s = latestState ?: return
        // The arc grows up the right edge (counter-clockwise), so that direction = louder.
        val steps = if (Tuning.VOLUME_FOLLOWS_ARC) -bezelSteps else bezelSteps
        volumeTouchedAt = SystemClock.uptimeMillis()
        if (s.maxVolume > 0) {
            val target = (s.volume + steps).coerceIn(0, s.maxVolume)
            if (target == s.volume) {
                haptics.edge()
                return
            }
            onVolume(target - s.volume)
        } else {
            onVolume(steps)
        }
        haptics.tick()
    }

    // ---- Liquid glass controls ----------------------------------------------------------------
    val spillLevel = remember { Animatable(0f) }
    var spillSource by remember { mutableIntStateOf(1) }

    /**
     * A control was used (0 = previous, 1 = play/pause, 2 = next): its drop swells and spills
     * into the buttons beside it through necks of clear liquid glass that cover almost all of
     * them, its colour filling most of each, then drains back into separate drops.
     */
    fun spillFrom(button: Int) {
        spillSource = button
        scope.launch {
            spillLevel.animateTo(1f, glassSpring(visibilityThreshold = 0.03f))
            spillLevel.animateTo(0f, glassSpring())
        }
    }

    val controlsAppear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { controlsAppear.animateTo(1f, glassSpring()) }
    val controlsSpot = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var controlsMoved by remember { mutableIntStateOf(0) }
    // Where the controls and the clock's pill last were (the glass is redrawn only when they move).
    val placedAt = remember { arrayOfNulls<Rect>(2) }
    // Ticks while the song plays, as often as the progress knob needs to glide.
    val knobClock = remember { mutableLongStateOf(0L) }
    // Where the clock's (or volume's) pill is, so the knob can melt into it too.
    val clockSpot = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // How far (px) the buttons rise (or, if negative, drop) from where the layout puts them, so
    // the Previous and Next buttons keep the same small gap from the progress tube as the volume
    // bar does.
    var controlsLift by remember { mutableFloatStateOf(0f) }
    // How far (px) the play button sits below the side buttons, so it too keeps that gap (the
    // buttons follow the curve of the tube).
    var playDrop by remember { mutableFloatStateOf(0f) }
    val scene = rememberGlassScene()

    /** Where the Next button's drop sits on the glass surface (null until laid out). */
    fun nextButtonCenter(density: Density): Offset? = controlsSpot[0]?.let { scene.boundsOf(it) }?.let { row ->
        Offset(row.center.x + with(density) { CONTROL_SIDE_DP.dp.toPx() }, row.center.y)
    }

    // ---- Input --------------------------------------------------------------------------------
    val bezelLatest by rememberUpdatedState<(Int) -> Unit>({ if (scrubbing) scrubBy(it) else volume(it) })
    // Returns false for presses the app should handle (4 = rate page).
    val pressLatest by rememberUpdatedState<(Int) -> Boolean>({ presses ->
        if (scrubbing) {
            if (presses == 2) commitScrub() else cancelScrub()
            true
        } else {
            when (presses) {
                1 -> {
                    haptics.confirm()
                    spillFrom(1)
                    onToggle()
                    true
                }
                2 -> {
                    onBack()
                    true
                }
                3 -> {
                    startScrub()
                    true
                }
                else -> false
            }
        }
    })
    SideEffect {
        if (active) actions.onPress = { presses -> pressLatest(presses) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .rotarySteps(active) { bezelLatest(it) },
    ) {
        if (state == null || current == null) {
            // Nothing playing (yet): behind the message, the song being started or else the last
            // one played, blurred.
            val shown = (if (starting != null) startingTrack else null) ?: state?.list?.firstOrNull()
            LaunchedEffect(shown?.artKey) { shown?.artKey?.let(requestArt) }
            Box(Modifier.fillMaxSize().glassBackdrop(scene)) {
                BlurredCover(shown, shown?.let { art[it.artKey] })
            }
            GlassSurface(scene)
            if (starting != null) {
                StartingMessage(scene, starting)
            } else {
                StatusMessage(Connection.Connected, hasState = state != null, player = state?.player.orEmpty(), scene = scene)
            }
            GlassClock(scene)
        } else {
            val density = LocalDensity.current
            val widthDp = with(density) { constraints.maxWidth.toDp() }
            val coverArt = art[current.artKey]
            val duration = current.durationMs
            // The sliders take a lighter (or, for pale covers, darker) tone of the album's colour,
            // and blend to the next album's.
            val sliderColor by animateColorAsState(
                CoverColors.sliderTone(coverColor(current, artColors)),
                tween(450),
                label = "sliderColor",
            )

            // The progress knob: where the song is (or, while scrubbing, the target). While the
            // song plays it's worked out afresh as often as it takes to glide (about every half
            // pixel it moves), not stepped with the clock; when it jumps (a seek, a scrub step, a
            // new song, the phone correcting its time) it springs to the new place, stretching on
            // its way like the Rate droplet.
            val knobBase: (Long) -> Float = { t ->
                when {
                    duration <= 0L -> 0f
                    scrubbing -> (scrubTarget.toFloat() / duration).coerceIn(0f, 1f)
                    else -> (state.positionAt(t).toFloat() / duration).coerceIn(0f, 1f)
                }
            }
            val knobBaseLatest by rememberUpdatedState(knobBase)
            val knobShown = remember { floatArrayOf(-1f) } // where it was last drawn
            val knobOffset = remember { Animatable(0f) } // springs back to 0 after a jump
            LaunchedEffect(state, scrubbing, scrubTarget, duration) {
                val now = System.currentTimeMillis()
                val jump = if (knobShown[0] < 0f) 0f else knobShown[0] - knobBaseLatest(now)
                if (abs(jump) < 0.0005f) {
                    knobOffset.snapTo(0f)
                } else {
                    knobOffset.snapTo(jump)
                    knobOffset.animateTo(0f, glassSpring())
                }
            }
            LaunchedEffect(state.isPlaying, active, duration, scrubbing) {
                if (!state.isPlaying || !active || scrubbing || duration <= 0L) return@LaunchedEffect
                // How often: about every half pixel of travel round the tube (at least each frame,
                // at most four times a second for very long songs).
                val ringPx = with(density) { (widthDp / 2f - RING_INSET_DP.dp).toPx() }
                val pxPerMs = 2f * PI.toFloat() * ringPx / duration
                val stepMs = (0.5f / pxPerMs).toLong().coerceIn(0L, 250L)
                while (true) {
                    withFrameMillis { knobClock.longValue = System.currentTimeMillis() }
                    if (stepMs > 16L) delay(stepMs - 16L)
                }
            }
            /** Where the knob is drawn now (0..1 round the tube from the top). */
            fun knobAt(): Float {
                knobClock.longValue // redraw as the clock moves
                val shown = (knobBaseLatest(System.currentTimeMillis()) + knobOffset.value).coerceIn(0f, 1f)
                knobShown[0] = shown
                return shown
            }

            // Behind the glass: the blurred cover (blur needs Wear OS 4+; older watches just get
            // it dimmed), and the tracks the glass knobs ride on, so the glass bends them.
            Box(Modifier.fillMaxSize().glassBackdrop(scene)) {
                CoverArt(current, coverArt, Modifier.fillMaxSize().blur(20.dp))
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Color.Black.copy(
                                alpha = when {
                                    scrubbing -> 0.7f
                                    Build.VERSION.SDK_INT >= 31 -> 0.55f
                                    else -> 0.75f
                                },
                            ),
                        ),
                )
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        controlsMoved // redraw when the controls move (the volume bar's length)
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val ring = min(size.width, size.height) / 2f - RING_INSET_DP.dp.toPx()
                        val volumeRing = ring - VOLUME_INSET_DP.dp.toPx()
                        val v = volumeAlpha.coerceIn(0f, 1f)
                        val hasVolume = (latestState?.maxVolume ?: 0) > 0
                        // Progress, round the edge from the top: the album colour up to the knob,
                        // inside its glass tube (the rest of the tube stays empty, like the volume
                        // bar's). The volume bar runs just inside it, never touching it.
                        // The line is a set share of the tube's thickness, centred in it. The domed
                        // crystal stretches what's drawn near the screen's edge outward, so it's
                        // drawn where (and as thin as) it must be to show there exactly.
                        val screenRadius = min(size.width, size.height) / 2f
                        val lineHalf = PROGRESS_TUBE_DP.dp.toPx() * PROGRESS_LINE_SHARE
                        val lineIn = domeSource(ring - lineHalf, screenRadius, this)
                        val lineOut = domeSource(ring + lineHalf, screenRadius, this)
                        val line = (lineIn + lineOut) / 2f
                        val at = knobAt()
                        if (at > 0.0005f) {
                            drawArc(
                                sliderColor, -90f, 360f * at, useCenter = false,
                                topLeft = Offset(c.x - line, c.y - line), size = Size(2f * line, 2f * line),
                                style = Stroke(lineOut - lineIn, cap = StrokeCap.Round),
                            )
                        }
                        if (scrubbing) {
                            // Where the song really is.
                            knobClock.longValue
                            val live = if (duration > 0) (state.positionAt(System.currentTimeMillis()).toFloat() / duration).coerceIn(0f, 1f) else 0f
                            val a = -PI.toFloat() / 2f + 2f * PI.toFloat() * live
                            drawCircle(Color.White.copy(alpha = 0.95f), 2.6.dp.toPx(), Offset(c.x + line * cos(a), c.y + line * sin(a)))
                        }
                        // The volume's level inside its glass pill, from the bottom up to the
                        // knob's middle.
                        val level = volumeAnim.value.coerceIn(0f, 1f)
                        if (v > 0.01f && hasVolume && level > 0.003f) {
                            val half = volumeHalfAngle(c, volumeRing, nextButtonCenter(this), this)
                            val halfDeg = half * 180f / PI.toFloat()
                            val knobAt = volumeKnobAngle(level, half, volumeRing, this) * 180f / PI.toFloat()
                            drawArc(
                                sliderColor.copy(alpha = 0.95f * v), halfDeg, knobAt - halfDeg, useCenter = false,
                                topLeft = Offset(c.x - volumeRing, c.y - volumeRing), size = Size(2f * volumeRing, 2f * volumeRing),
                                style = Stroke(2f * (VOLUME_TUBE_DP - VOLUME_FILL_INSET_DP).dp.toPx() * v, cap = StrokeCap.Round),
                            )
                        }
                    },
                )
            }
            // The liquid glass: the domed crystal over the face; the three control drops (and the
            // liquid flooding between them when one is used); the progress knob; while the bezel
            // turns, the volume tube and its lens; and the label (Modifier.glass, below).
            GlassSurface(scene) {
                controlsMoved // redraw when the controls move
                val shapes = ArrayList<GlassShape>(14)
                val appear = controlsAppear.value
                val row = controlsSpot[0]?.let { scene.boundsOf(it) }
                if (row != null && appear > 0.01f) {
                    val c = row.center
                    val side = CONTROL_SIDE_DP.dp.toPx() // from the play button's centre to a side button's
                    val xs = floatArrayOf(c.x - side, c.x, c.x + side)
                    val ys = floatArrayOf(c.y, c.y + playDrop, c.y) // the play button sits a little lower
                    val radii = floatArrayOf(SIDE_BUTTON_RADIUS_DP.dp.toPx(), PLAY_BUTTON_RADIUS_DP.dp.toPx(), SIDE_BUTTON_RADIUS_DP.dp.toPx())
                    // Play/pause in the album colour, like the sliders.
                    val looks = arrayOf(GlassLooks.Button, GlassLooks.tinted(sliderColor), GlassLooks.Button)
                    val s = spillLevel.value.coerceAtLeast(0f)
                    val from = spillSource
                    val run = smoothStep(0f, 0.5f, s)
                    val spread = smoothStep(0.25f, 0.75f, s)
                    val rs = FloatArray(3) { i -> radii[i] * appear * (if (i == from) 1f + 0.12f * s else 1f) }
                    // While a button spills, its drop melts into each neighbour through a neck
                    // whose sides are one smooth curve, from the edge of one button into the
                    // other (the liquid merge, reaching just far enough to make the neck
                    // SPILL_NECK_MIDDLE thick at its narrowest). It's set on the later of the
                    // two, since each piece of glass melts into the glass listed before it.
                    val merges = FloatArray(3)
                    if (spread > 0.01f) {
                        for (j in intArrayOf(from - 1, from + 1)) {
                            if (j !in 0..2) continue
                            val waist = SPILL_NECK_MIDDLE * min(radii[from], radii[j]) * spread
                            merges[max(from, j)] = bridgeMerge(xs[from], ys[from], rs[from], xs[j], ys[j], rs[j], waist)
                        }
                    }
                    for (i in 0..2) {
                        val r = rs[i]
                        shapes += GlassShape(Rect(xs[i] - r, ys[i] - r, xs[i] + r, ys[i] + r), looks[i].copy(merge = merges[i].toDp()))
                    }
                    if (s > 0.01f) {
                        // A drop of clear liquid glass runs out of the used button to each
                        // neighbour (the way the progress knob melts into a button) and spreads
                        // over it until it covers it almost completely; the used button's colour
                        // flows along just behind it and fills most of the neighbour, short of
                        // the clear glass's edge. Then both drain back.
                        val blob = (6f + 5f * s).dp.toPx()
                        val neck = GlassLooks.SpillNeck.copy(merge = KNOB_BUTTON_MERGE_DP.dp)
                        // The colour melts into the neighbour's glass and the clear glass over it
                        // (no lens edge where it ends), sharing their spot, so it's laid on twice
                        // as strong to keep the colour of the button it came from.
                        val dye = looks[from].let { it.copy(tintAmount = it.tintAmount * 2f, merge = KNOB_BUTTON_MERGE_DP.dp) }
                        for (j in intArrayOf(from - 1, from + 1)) {
                            if (j !in 0..2) continue
                            // Along the way from the used button to the neighbour: a drop halfway
                            // that keeps the liquid joined while it runs out (the curved neck
                            // takes over as it spreads); the clear glass's front, settling over
                            // the neighbour and melting in less as it does, so it doesn't swell
                            // it; and the colour just behind it, catching up as it settles.
                            val drops = arrayOf(
                                Triple(run * 0.5f, blob * 0.8f * (1f - spread), neck),
                                Triple(run, blob + (SPILL_NECK_COVER * radii[j] - blob) * spread, neck.copy(merge = (KNOB_BUTTON_MERGE_DP - (KNOB_BUTTON_MERGE_DP - 2f) * spread).dp)),
                                Triple(run * (0.92f + 0.08f * spread), blob * 0.6f + (SPILL_COLOR_COVER * radii[j] - blob * 0.6f) * spread, dye),
                            )
                            for ((along, r, look) in drops) {
                                if (r < 0.5f) continue
                                val x = xs[from] + (xs[j] - xs[from]) * along
                                val y = ys[from] + (ys[j] - ys[from]) * along
                                shapes += GlassShape(Rect(x - r, y - r, x + r, y + r), look)
                            }
                        }
                    }
                }

                val center = Offset(size.width / 2f, size.height / 2f)
                val ring = min(size.width, size.height) / 2f - RING_INSET_DP.dp.toPx()

                // The progress knob: a clear glass capsule riding the tube at the end of the colour,
                // a little thicker than the tube, magnifying and bending the line (with its own rim
                // inside the tube). On the move it stretches along its way and thins. Where the
                // volume bar lies over the tube, it melts away while the bar shows. Passing over
                // a button, it melts into the button's drop through a liquid neck (it comes right
                // after the buttons, so only they can fuse with it; the tube comes after it and
                // never fuses with anything).
                val v = volumeAlpha.coerceAtMost(1.2f)
                val hasVolume = (latestState?.maxVolume ?: 0) > 0
                val volumeRing = ring - VOLUME_INSET_DP.dp.toPx()
                val volumeHalf = volumeHalfAngle(center, volumeRing, nextButtonCenter(this), this)
                val at = knobAt()
                val knobDeg = ((-90f + 360f * at + 540f) % 360f) - 180f // 0 = the right-hand side
                val underPill = if (hasVolume) {
                    ((volumeReachDeg(ring, volumeRing, volumeHalf, this) + 12f - abs(knobDeg)) / 12f).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val knobSize = 1f - v.coerceIn(0f, 1f) * underPill
                if (knobSize > 0.05f) {
                    val motion = (abs(knobOffset.velocity) / KNOB_FULL_STRETCH).coerceIn(0f, 1f)
                    val across = KNOB_HALF_THICK_DP.dp.toPx() * (1f - 0.15f * motion) * knobSize
                    val along = KNOB_HALF_LEN_DP.dp.toPx() * (1f + 0.30f * motion) * knobSize
                    val angle = -PI.toFloat() / 2f + 2f * PI.toFloat() * at
                    val knobAt = Offset(center.x + ring * cos(angle), center.y + ring * sin(angle))
                    val onButton = max(
                        buttonsNear(knobAt, across, row, playDrop, this),
                        pillNear(knobAt, across, clockSpot[0]?.let { scene.boundsOf(it) }, this),
                    )
                    val look = GlassLooks.sliderKnob(motion)
                    shapes += GlassShape.arc(
                        GlassArc(center, ring, across, angle, max((along - across) / ring, 0.0001f)),
                        look.copy(merge = max((look.merge ?: 0.dp).value, KNOB_BUTTON_MERGE_DP * onButton).dp),
                    )
                }

                // The progress tube: a ring of frosted glass round the edge, holding the line.
                shapes += GlassShape.arc(GlassArc(center, ring, PROGRESS_TUBE_DP.dp.toPx(), 0f, PI.toFloat()), GlassLooks.ProgressTube)

                // The volume along the right edge: a curved pill of frosted glass with the
                // album-coloured level inside it (drawn behind), and a clear glass knob at the
                // level that flows to each new level.
                if (v > 0.01f && hasVolume) {
                    shapes += GlassShape.arc(GlassArc(center, volumeRing, VOLUME_TUBE_DP.dp.toPx() * v, 0f, volumeHalf), GlassLooks.VolumeTube)
                    val level = volumeAnim.value.coerceIn(0f, 1f)
                    val knobMotion = (abs(volumeAnim.velocity) / LENS_FULL_STRETCH).coerceIn(0f, 1f)
                    val knobAcross = VOLUME_KNOB_HALF_THICK_DP.dp.toPx() * v * (1f - 0.15f * knobMotion)
                    val knobAlong = VOLUME_KNOB_HALF_LEN_DP.dp.toPx() * v * (1f + 0.30f * knobMotion)
                    shapes += GlassShape.arc(
                        GlassArc(center, volumeRing, knobAcross, volumeKnobAngle(level, volumeHalf, volumeRing, this), max((knobAlong - knobAcross) / volumeRing, 0.0001f)),
                        GlassLooks.volumeKnob(knobMotion),
                    )
                }
                shapes
            }

            Column(
                // A little below centre, leaving room for the clock under the progress tube.
                Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Tap the cover to rate, tap the title for lyrics.
                Box(
                    Modifier
                        .size(widthDp * if (scrubbing) 0.24f else 0.33f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !scrubbing) {
                            haptics.confirm()
                            onRate()
                        },
                ) {
                    CoverArt(current, coverArt, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(6.dp))
                MarqueeText(
                    current.title,
                    14.sp,
                    Color.White,
                    Modifier
                        .fillMaxWidth(0.82f)
                        .clickable(enabled = !scrubbing) {
                            haptics.confirm()
                            onLyrics()
                        },
                    FontWeight.SemiBold,
                )

                if (scrubbing) {
                    Text(
                        text = formatTime(scrubTarget),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    PlayedTime(state, duration)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Back ×2 jump  ·  ×1 cancel",
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                } else {
                    if (current.artist.isNotBlank()) {
                        MarqueeText(current.artist, 11.sp, Color.White.copy(alpha = 0.8f), Modifier.fillMaxWidth(0.8f))
                    }
                    if (current.album.isNotBlank()) {
                        MarqueeText(current.album, 10.sp, Color.White.copy(alpha = 0.55f), Modifier.fillMaxWidth(0.74f))
                    }
                    Spacer(Modifier.height(6.dp))
                    // The buttons; their glass (three drops) is drawn by the glass surface above,
                    // placed from where this row lands. The row then moves up or down (it depends on
                    // the screen and the text sizes) so the side buttons sit the same small gap
                    // from the progress tube as the volume bar does.
                    Box(
                        Modifier.onGloballyPositioned { natural ->
                            val (lift, drop) = controlsPlacementFor(natural, density)
                            if (abs(lift - controlsLift) > 0.5f) controlsLift = lift
                            if (abs(drop - playDrop) > 0.5f) playDrop = drop
                        },
                    ) {
                        Row(
                            Modifier
                                .offset { IntOffset(0, -controlsLift.roundToInt()) }
                                .onGloballyPositioned {
                                    controlsSpot[0] = it
                                    val b = it.boundsInRoot()
                                    if (b != placedAt[0]) {
                                        placedAt[0] = b
                                        controlsMoved++
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ControlButton(Glyph.Previous, 30.dp, 12.dp) {
                                haptics.confirm()
                                spillFrom(0)
                                onPrevious()
                            }
                            ControlButton(
                                if (state.isPlaying) Glyph.Pause else Glyph.Play,
                                (2f * PLAY_BUTTON_RADIUS_DP).dp,
                                16.dp,
                                Modifier.offset { IntOffset(0, playDrop.roundToInt()) },
                            ) {
                                haptics.confirm()
                                spillFrom(1)
                                onToggle()
                            }
                            ControlButton(Glyph.Next, 30.dp, 12.dp) {
                                haptics.confirm()
                                spillFrom(2)
                                onNext()
                            }
                        }
                    }
                }
            }

            // The clock, on a pill of smoked glass; while scrubbing or changing the volume, the
            // same pill shows the mode or the volume instead.
            GlassClock(
                scene = scene,
                // Just below the progress tube.
                top = (RING_INSET_DP + PROGRESS_TUBE_DP + 2.5f).dp,
                text = when {
                    scrubbing -> "SCRUB"
                    showVolume && state.maxVolume > 0 -> "Volume ${(state.volume * 100f / state.maxVolume).roundToInt()}%"
                    else -> null
                },
                look = if (scrubbing) GlassLooks.tinted(Tuning.AccentDeep, 0.8f).copy(merge = 0.dp) else ClockLook,
                letterSpacing = if (scrubbing) 1.5.sp else 0.sp,
                onPlaced = {
                    clockSpot[0] = it
                    val b = it.boundsInRoot()
                    if (b != placedAt[1]) {
                        placedAt[1] = b
                        controlsMoved++
                    }
                },
            )
        }
    }
}

@Composable
private fun StartingMessage(scene: GlassScene, app: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .glass(scene, GlassLooks.Card)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.wear.compose.material.CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                indicatorColor = Tuning.Accent,
            )
            Text("Starting in $app…", fontSize = 14.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/**
 * The progress slider (dp), built like the volume bar: how far inside the screen's edge its
 * glass tube and knob run, half the tube's thickness, and how much of the tube's thickness the
 * coloured line fills (a share, as the volume bar's level fills its bar); half the knob's
 * thickness and length, and how fast (songs per second) it moves at full stretch.
 */
private const val RING_INSET_DP = 10.5f
private const val PROGRESS_TUBE_DP = 3.5f
private const val PROGRESS_LINE_SHARE = 0.45f
private const val KNOB_HALF_THICK_DP = 7.5f
private const val KNOB_HALF_LEN_DP = 13f
private const val KNOB_FULL_STRETCH = 1.2f

/**
 * The volume: the gap it keeps from the progress tube and from the Next button (dp; just enough
 * that they never touch, while the bar's rim, which gathers what lies a few dp past its edge,
 * still catches the progress line's light), half the bar's thickness (dp; its bottom end comes
 * down to that gap from the Next button, and the top end mirrors it), its reach before the
 * buttons are laid out and the least and most it may reach (degrees each way from the right-hand
 * side), how far inside the bar's edge its coloured level runs (dp), half the knob's thickness and
 * length (dp), and how fast (whole volume ranges per second) the knob moves at full stretch.
 */
private const val GLASS_GAP_DP = 1.5f // also kept between the progress tube and the Previous/Next buttons
private const val VOLUME_TUBE_DP = 12f

/** How far inside the progress tube's middle the volume bar's middle runs (dp). */
private const val VOLUME_INSET_DP = PROGRESS_TUBE_DP + GLASS_GAP_DP + VOLUME_TUBE_DP
private const val VOLUME_HALF_DEG = 40f
private const val VOLUME_MIN_HALF_DEG = 20f
private const val VOLUME_MAX_HALF_DEG = 55f
private const val VOLUME_FILL_INSET_DP = 7f
private const val VOLUME_KNOB_HALF_THICK_DP = 10.8f
private const val VOLUME_KNOB_HALF_LEN_DP = 15f
private const val LENS_FULL_STRETCH = 3f

/**
 * The transport buttons: from the play button's centre to a side button's, and a side button's
 * and the play button's radius (dp).
 */
private const val CONTROL_SIDE_DP = 45f
private const val SIDE_BUTTON_RADIUS_DP = 15f
private const val PLAY_BUTTON_RADIUS_DP = 20f

/**
 * How far (dp) the progress knob reaches to melt into a button or the clock's pill it passes
 * over (and the liquid a used button spills into its neighbours).
 */
private const val KNOB_BUTTON_MERGE_DP = 9f

/**
 * When a used button spills into its neighbours, at its fullest: how much of a neighbour the
 * clear glass covers, and how much of it the used button's colour fills (fractions of the
 * neighbour's radius); and how thick the curved neck between them is at its narrowest, each
 * side of its middle line (a fraction of the smaller button's radius).
 */
private const val SPILL_NECK_COVER = 0.98f
private const val SPILL_COLOR_COVER = 0.92f
private const val SPILL_NECK_MIDDLE = 0.85f

/** The most (dp) the buttons row moves up or down to keep its gap from the progress tube. */
private const val CONTROLS_MAX_MOVE_DP = 16f

/**
 * Where the buttons go, from where the row is laid out ([natural]): how far (px) the row must
 * rise (negative: drop) so its side buttons sit exactly [GLASS_GAP_DP] from the progress tube's
 * inner edge, and how far the play button then sits below them so it keeps the same gap (each
 * at most [CONTROLS_MAX_MOVE_DP]).
 */
private fun controlsPlacementFor(natural: LayoutCoordinates, density: Density): Pair<Float, Float> {
    if (!natural.isAttached) return 0f to 0f
    val root = natural.findRootCoordinates().size
    val box = natural.boundsInRoot()
    val centerX = root.width / 2f
    val centerY = root.height / 2f
    return with(density) {
        val tubeInside = min(root.width, root.height) / 2f - (RING_INSET_DP + PROGRESS_TUBE_DP + GLASS_GAP_DP).dp.toPx()
        val most = CONTROLS_MAX_MOVE_DP.dp.toPx()
        val offCentre = abs(box.center.x - centerX)
        // The side buttons' centres must lie this far from the screen's centre.
        val sideReach = tubeInside - SIDE_BUTTON_RADIUS_DP.dp.toPx()
        val sideDx = offCentre + CONTROL_SIDE_DP.dp.toPx()
        val below = box.center.y - centerY
        val lift = (below - sqrt(max(sideReach * sideReach - sideDx * sideDx, 0f))).coerceIn(-most, most)
        // And the play button's this far.
        val playReach = tubeInside - PLAY_BUTTON_RADIUS_DP.dp.toPx()
        val playY = sqrt(max(playReach * playReach - offCentre * offCentre, 0f))
        val drop = (playY - (below - lift)).coerceIn(-most, most)
        lift to drop
    }
}

/**
 * How much (0..1) the progress knob, centred at [knob] and reaching [across] px, lies on the
 * clock's pill at [pill]: 0 when clear of it, 1 once it overlaps it.
 */
private fun pillNear(knob: Offset, across: Float, pill: Rect?, density: Density): Float {
    if (pill == null) return 0f
    return with(density) {
        val dx = max(abs(knob.x - pill.center.x) - pill.width / 2f, 0f)
        val dy = max(abs(knob.y - pill.center.y) - pill.height / 2f, 0f)
        val gap = hypot(dx, dy) - across
        val far = 4.dp.toPx()
        val near = ((far - gap) / (far + 2.dp.toPx())).coerceIn(0f, 1f)
        near * near * (3f - 2f * near)
    }
}

/**
 * How much (0..1) the progress knob, centred at [knob] and reaching [across] px inward, lies on
 * one of the buttons (the row at [row], its play button [playDrop] px lower): 0 when clear of
 * them all, 1 once it overlaps one.
 */
private fun buttonsNear(knob: Offset, across: Float, row: Rect?, playDrop: Float, density: Density): Float {
    if (row == null) return 0f
    return with(density) {
        val side = CONTROL_SIDE_DP.dp.toPx()
        val far = 4.dp.toPx()
        var near = 0f
        val xs = floatArrayOf(row.center.x - side, row.center.x, row.center.x + side)
        val ys = floatArrayOf(row.center.y, row.center.y + playDrop, row.center.y)
        val radii = floatArrayOf(SIDE_BUTTON_RADIUS_DP, PLAY_BUTTON_RADIUS_DP, SIDE_BUTTON_RADIUS_DP)
        for (k in 0..2) {
            val gap = hypot(knob.x - xs[k], knob.y - ys[k]) - (across + radii[k].dp.toPx())
            near = max(near, ((far - gap) / (far + 2.dp.toPx())).coerceIn(0f, 1f))
        }
        near * near * (3f - 2f * near)
    }
}

/**
 * Half the volume bar's opening (radians, each way from the right-hand side): long enough for
 * its bottom end to come down to just short of the Next button at [next] (where its drop sits on
 * screen); the top end mirrors it. Until the buttons are laid out, [VOLUME_HALF_DEG].
 */
private fun volumeHalfAngle(center: Offset, volumeRing: Float, next: Offset?, density: Density): Float {
    val toRad = PI.toFloat() / 180f
    if (next == null) return VOLUME_HALF_DEG * toRad
    val reach = with(density) { (VOLUME_TUBE_DP + SIDE_BUTTON_RADIUS_DP + GLASS_GAP_DP).dp.toPx() }
    // How far the bar's round end, reaching down to [a], stays from touching the button.
    fun clearance(a: Float): Float = hypot(center.x + volumeRing * cos(a) - next.x, center.y + volumeRing * sin(a) - next.y) - reach
    val toward = atan2(next.y - center.y, next.x - center.x) // the button's own direction
    val half = when {
        toward <= 0f || clearance(0f) <= 0f -> VOLUME_MIN_HALF_DEG * toRad
        clearance(toward) > 0f -> toward
        else -> {
            var lo = 0f
            var hi = toward
            repeat(24) {
                val mid = (lo + hi) / 2f
                if (clearance(mid) > 0f) lo = mid else hi = mid
            }
            lo
        }
    }
    return half.coerceIn(VOLUME_MIN_HALF_DEG * toRad, VOLUME_MAX_HALF_DEG * toRad)
}

/**
 * Where the volume knob's middle sits at [level] (radians, screen angles: 0 = the right-hand
 * side) on a bar opening [half] radians each way: it travels from the bar's bottom end to its top
 * end, never poking out past them.
 */
private fun volumeKnobAngle(level: Float, half: Float, volumeRing: Float, density: Density): Float {
    val inset = with(density) { max((VOLUME_KNOB_HALF_LEN_DP - VOLUME_TUBE_DP + 1.5f).dp.toPx(), 0f) } / volumeRing
    val travel = half - inset
    return travel - 2f * travel * level
}

/**
 * How far round from the right-hand side (degrees) the volume bar, opening [half] radians each
 * way, comes near the progress knob's path, round ends included: the knob, which reaches a little
 * past its tube, melts away there while the bar shows.
 */
private fun volumeReachDeg(ring: Float, volumeRing: Float, half: Float, density: Density): Float {
    val reach = with(density) { (VOLUME_TUBE_DP + KNOB_HALF_THICK_DP + 2f).dp.toPx() }
    val cosEnd = ((ring * ring + volumeRing * volumeRing - reach * reach) / (2f * ring * volumeRing)).coerceIn(-1f, 1f)
    return (half + acos(cosEnd)) * 180f / PI.toFloat()
}

/**
 * The domed crystal over the face (see GLASS_SHADER in Glass.kt) shows, at [r] px from the
 * centre, what is drawn at the radius returned: near the screen's edge ([screenRadius] px) it
 * stretches the picture outward. Drawing something at domeSource(r) puts it on screen at r.
 */
private fun domeSource(r: Float, screenRadius: Float, density: Density): Float {
    val rim = with(density) { 18.dp.toPx() }
    val t = ((screenRadius - r) / rim).coerceIn(0f, 1f)
    return r - (1f - t) * (1f - t) * rim * 0.55f * Tuning.GLASS_SCREEN
}

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * How far (px) one round drop of glass (centre [x2], [y2], radius [r2]) must reach to melt into
 * another ([x1], [y1], [r1]) through a neck [waist] px thick each side of its middle line at its
 * narrowest. The glass's liquid merge draws the neck's sides as one smooth curve from one drop
 * into the other; this finds the reach that brings that curve [waist] out from the line
 * everywhere across the gap.
 */
private fun bridgeMerge(x1: Float, y1: Float, r1: Float, x2: Float, y2: Float, r2: Float, waist: Float): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    val d = hypot(dx, dy)
    if (d < 1f) return 0f
    val ux = dx / d
    val uy = dy / d
    var reach = 0f
    for (i in 0..8) {
        val along = r1 + (d - r1 - r2) * i / 8f // across the gap between the drops
        for (side in intArrayOf(1, -1)) {
            val px = x1 + ux * along - uy * waist * side
            val py = y1 + uy * along + ux * waist * side
            reach = max(reach, reachToJoin(hypot(px - x1, py - y1) - r1, hypot(px - x2, py - y2) - r2))
        }
    }
    return reach
}

/**
 * The merge reach at which a point [a] px from one piece of glass and [b] px from another comes
 * onto the melted surface (the shader's smooth minimum: min(a, b) - (k - |a - b|)³ / 6k²).
 */
private fun reachToJoin(a: Float, b: Float): Float {
    val near = min(a, b)
    if (near <= 0f) return 0f
    val apart = abs(a - b)
    fun dip(k: Float): Float {
        val e = k - apart
        return e * e * e / (6f * k * k)
    }
    var lo = apart
    var hi = apart + 1f
    while (dip(hi) < near) hi *= 2f
    repeat(24) {
        val mid = (lo + hi) / 2f
        if (dip(mid) < near) lo = mid else hi = mid
    }
    return hi
}

/**
 * "now 1:23 · 4:05" under the scrub target: where the song really is, ticking on its own (so
 * the rest of the screen isn't rebuilt for it).
 */
@Composable
private fun PlayedTime(state: PlayerState, duration: Long) {
    val latest by rememberUpdatedState(state)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    Text(
        text = "now ${formatTime(latest.positionAt(now))}" + (if (duration > 0) "  ·  ${formatTime(duration)}" else ""),
        fontSize = 10.sp,
        color = Color.White.copy(alpha = 0.65f),
    )
}

/** A transport button: just the icon and the touch area (its glass drop is drawn by the glass surface). */
@Composable
private fun ControlButton(glyph: Glyph, size: Dp, iconSize: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, Color.White, Modifier.size(iconSize))
    }
}
