package com.coverflow.wear.ui

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coverflow.wear.Tuning
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.first

/*
 * Liquid glass.
 *
 * Each screen is drawn in three layers:
 *   1. the backdrop: its background, plus whatever the glass should bend (the covers in Cover
 *      Flow, the track titles on the album page), marked with Modifier.glassBackdrop;
 *   2. one GlassSurface, which redraws the backdrop through a single GPU shader;
 *   3. everything that sits on the glass (text, icons), drawn normally on top.
 *
 * The shader (GLASS_SHADER below) treats the whole watch face as a slightly domed crystal
 * (the picture stretches a little toward the rim), and draws every glass shape on the screen as
 * one liquid surface: shapes fuse through a smooth liquid neck where they come close (how close
 * is set per surface), and each one acts like a thick slab of glass with a rounded rim, after
 * Apple's Liquid Glass. The rim gathers in what lies beyond the edge and wraps it around the
 * inside of the outline, so lines behind the glass bend into contours that follow its shape,
 * with a short rainbow along the bottom edge. Light catches the rim (a soft glow inside the
 * edge, a hair-thin highlight, brightest top left and bottom right, and a faint darker
 * outline), the curved rim is lighter where it turns toward the light and darker where it turns
 * away, and bodies can be frosted (milky, like the Figma Liquid Glass kit), magnified and
 * tinted. A soft shadow lifts the glass off the page. Tuning.GLASS_* turns each part up or down.
 *
 * Shapes come from Modifier.glass (buttons, labels, cards; they grow in like droplets when
 * they appear and shrink away when they leave) and from shape lists a screen computes itself
 * (the rolling track list).
 *
 * The shader needs Android 13 (Wear OS 4). On older watches, or if it ever fails to start, the
 * same shapes are drawn as plain see-through panels instead.
 */

private const val TAG = "CoverFlowGlass"
private const val MAX_SHAPES = 16
private const val APPEAR_NS = 450_000_000L
private const val VANISH_NS = 240_000_000L

/** How a piece of glass looks. Sizes are in dp. */
@Immutable
data class GlassLook(
    /** Corner radius; null = fully rounded (a pill, or a circle for square shapes). */
    val corner: Dp? = null,
    /** Tint colour at the left edge and at the right edge (a gradient across the shape). */
    val tint: Color = Color.White,
    val tintEnd: Color = tint,
    /** How strongly the tint covers what's behind (0 = clear glass). */
    val tintAmount: Float = 0f,
    /** Blur radius of what's seen through the glass (0 = perfectly clear). */
    val frost: Dp = 0.dp,
    /** Magnification of what's behind (1 = none). */
    val zoom: Float = 1f,
    /** Width of the rounded rim where the light bends (capped at the shape's own size). */
    val rim: Dp = 8.dp,
    /** How far past the edge the rim reaches for what it wraps inside (at the very edge). */
    val refraction: Dp = 10.dp,
    /** How far red and blue split apart at the rim (the faint rainbow). */
    val fringe: Dp = 2.5.dp,
    /**
     * How close this piece must come to the glass listed before it to melt into it
     * (0 = never); null = the surface's setting.
     */
    val merge: Dp? = null,
    /** A lens laid over other glass: where it lies, its look replaces theirs. */
    val over: Boolean = false,
    /**
     * A lens that also keeps its own outline inside other glass (implies [over]): where it lies,
     * the glass takes its shape from it alone, so it shows its own rim, light and bend.
     */
    val lens: Boolean = false,
)

object GlassLooks {
    /** Small labels: frosted, milky white. */
    val Pill = GlassLook(tintAmount = 0.16f, frost = 4.dp, rim = 4.dp, refraction = 6.dp, fringe = 1.5.dp)

    /** Dark smoked glass, for labels over busy backgrounds. */
    val Smoked = GlassLook(tint = Color.Black, tintAmount = 0.35f, frost = 5.dp, rim = 4.dp, refraction = 5.dp, fringe = 1.5.dp)

    /** Round buttons: frosted, milky white glass. */
    val Button = GlassLook(tintAmount = 0.22f, frost = 4.dp, rim = 8.dp, refraction = 11.dp, fringe = 2.dp)

    /** Message cards. */
    val Card = GlassLook(corner = 24.dp, tintAmount = 0.14f, frost = 6.dp, rim = 10.dp, refraction = 13.dp, fringe = 2.dp)

    /** Coloured glass (the play button, Save): lighter at the left, the colour at the right. */
    fun tinted(color: Color, amount: Float = 0.72f) = GlassLook(
        tint = lerp(color, Color.White, 0.35f),
        tintEnd = color,
        tintAmount = amount,
        frost = 3.dp,
        rim = 9.dp,
        refraction = 12.dp,
        fringe = 2.dp,
    )

    /** The circle behind each star on the Rate page: clear glass that never fuses with its neighbours. */
    val StarCircle = GlassLook(tintAmount = 0.10f, rim = 5.dp, refraction = 7.dp, fringe = 1.5.dp, merge = 0.dp)

    /**
     * The Rate page's travelling droplet: a lens of [color] glass that magnifies the star under
     * it. [motion] is 0 when it rests on a star and 1 halfway to the next: on the way it
     * thins, bends what's behind it harder and melts into the circles it passes.
     */
    fun droplet(color: Color, motion: Float): GlassLook = droplet(
        left = lerp(color, Color.White, 0.45f),
        right = color,
        motion = motion,
        amount = 0.30f,
        zoom = 1.14f,
        zoomGrow = 0.18f,
        rim = 7.dp,
        refraction = 11f,
        bend = 1f,
    )

    /**
     * A travelling selection droplet in general: a lens filled with a [left]-to-[right]
     * gradient ([amount] strong) that magnifies by [zoom] where it rests. [motion] is 0 at rest
     * and 1 halfway to the next spot; on the way it fades a little, magnifies by [zoomGrow]
     * more, bends [bend] times harder (from [refraction] dp) and melts into the glass it passes.
     */
    fun droplet(
        left: Color,
        right: Color,
        motion: Float,
        amount: Float,
        zoom: Float,
        zoomGrow: Float,
        rim: Dp,
        refraction: Float,
        bend: Float,
    ): GlassLook {
        val m = motion.coerceIn(0f, 1f)
        val ramp = (m / 0.2f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        return GlassLook(
            tint = left,
            tintEnd = right,
            tintAmount = amount * (1f - 0.35f * m),
            zoom = zoom + zoomGrow * m,
            rim = rim,
            refraction = (refraction * (1f + bend * m)).dp,
            fringe = 2.5.dp,
            merge = (8f * ramp).dp,
            over = true,
        )
    }

    /** The track list's selection droplets (one for the number, one for the name). */
    fun trackDroplet(left: Color, right: Color, motion: Float): GlassLook =
        droplet(left, right, motion, amount = 0.55f, zoom = 1.03f, zoomGrow = 0.06f, rim = 6.dp, refraction = 10f, bend = 0.5f)

    /**
     * The progress slider's knob, after Apple's Liquid Glass sliders: a clear oval lens riding
     * its glass tube, magnifying the coloured line under it, with its own rim inside the tube.
     * [motion] (0 at rest, 1 at full speed) stretches it, bends harder and lets it melt into
     * nearby glass, like the Rate droplet.
     */
    fun sliderKnob(motion: Float): GlassLook = droplet(
        left = Color.White,
        right = Color.White,
        motion = motion,
        amount = 0.06f,
        zoom = 1.22f,
        zoomGrow = 0.10f,
        rim = 6.dp,
        refraction = 9f,
        bend = 0.6f,
    ).copy(lens = true)

    /**
     * Now Playing's spill: the clear liquid glass a used button sends into the buttons beside
     * it. It bends the light just as the buttons do, so where it covers one it melts into the
     * button's own glass rather than sitting on it as a lens.
     */
    val SpillNeck = GlassLook(tintAmount = 0.06f, rim = 8.dp, refraction = 11.dp, fringe = 2.dp)

    /** The progress slider's tube: a thin ring of lightly frosted glass (so the thin line in it stays crisp). */
    val ProgressTube = GlassLook(tintAmount = 0.12f, frost = 0.8.dp, rim = 3.dp, refraction = 4.dp, fringe = 1.dp, merge = 0.dp)

    /** The volume slider: a curved pill of frosted glass ... */
    val VolumeTube = GlassLook(tintAmount = 0.16f, frost = 2.5.dp, rim = 5.5.dp, refraction = 7.dp, fringe = 2.dp, merge = 0.dp)

    /**
     * ... with a clear glass knob inside it at the level, magnifying the album-coloured fill
     * under it, with its own rim inside the pill ([motion] as for [sliderKnob]).
     */
    fun volumeKnob(motion: Float): GlassLook = droplet(
        left = Color.White,
        right = Color.White,
        motion = motion,
        amount = 0.10f,
        zoom = 1.18f,
        zoomGrow = 0.10f,
        rim = 6.dp,
        refraction = 9f,
        bend = 0.6f,
    ).copy(lens = true)

    /**
     * Cover Flow's pane of glass at the centre, a little larger than the cover that rests in it.
     * [moving] (0 at rest, 1 with a cover halfway in or out) makes it bend the light harder and
     * magnify a touch.
     */
    fun coverPane(moving: Float): GlassLook {
        val m = moving.coerceIn(0f, 1f)
        return GlassLook(
            corner = 12.dp,
            tintAmount = 0.06f,
            zoom = 1f + 0.04f * m,
            rim = 9.dp,
            refraction = (12f * (1f + 0.9f * m)).dp,
            fringe = (2.5f * (1f + 0.6f * m)).dp,
            merge = 0.dp,
        )
    }

    /** A piece of a track row (its number bubble or its name capsule): clear glass. */
    val TrackPiece = GlassLook(rim = 6.dp, refraction = 9.dp, fringe = 2.dp)
}

/**
 * A glass shape a screen computes itself, in the glass surface's pixels: a rounded box filling
 * [rect], its left and right sides bowed out (+) or in (−) by [bendLeft] and [bendRight] px at
 * their middle; or, with [arc], a curved tube; or, with [chain], a chain of beads.
 */
class GlassShape(
    val rect: Rect,
    val look: GlassLook,
    val arc: GlassArc? = null,
    val chain: GlassChain? = null,
    val bendLeft: Float = 0f,
    val bendRight: Float = 0f,
) {
    companion object {
        /** A curved tube of glass (see [GlassArc]). */
        fun arc(arc: GlassArc, look: GlassLook) = GlassShape(Rect(arc.center, arc.radius), look, arc = arc)

        /** A chain of glass beads (see [GlassChain]). */
        fun chain(chain: GlassChain, look: GlassLook) = GlassShape(Rect(chain.center, chain.radius), look, chain = chain)
    }
}

/**
 * Glass beads of [beadRadius] (px) on the circle of [radius] around [center]: bead n sits at the
 * angle [firstAngle] + n × [step] (radians; 0 = right, increasing clockwise). Beads [start] up to
 * [start] + [count] are there, the last one grown only by the fraction of [count] (so a chain
 * can creep forward bead by bead); beads closer than [innerMerge] (px) melt together.
 */
data class GlassChain(
    val center: Offset,
    val radius: Float,
    val beadRadius: Float,
    val firstAngle: Float,
    val step: Float,
    val start: Float,
    val count: Float,
    val innerMerge: Float,
)

/**
 * A curved tube with round ends: it runs along the circle of [radius] around [center] (px),
 * [halfThickness] thick on each side, opening [halfAperture] radians each way from the
 * direction [middle] (radians; 0 = right, increasing clockwise, as Compose's drawArc).
 */
data class GlassArc(
    val center: Offset,
    val radius: Float,
    val halfThickness: Float,
    val middle: Float,
    val halfAperture: Float,
)

/**
 * A shape ready for drawing, in pixels, after the droplet animation. A rounded box; or with
 * [aperture] > 0 an arc: then (cx, cy) is the ring's centre, hw its radius, hh the tube's half
 * thickness and corner the direction of its middle; or with [aperture] < 0 a chain of beads:
 * (cx, cy) and hw the ring, hh the bead radius, corner the angle of bead 0, and [chain] the rest.
 */
private class Resolved(
    val cx: Float,
    val cy: Float,
    val hw: Float,
    val hh: Float,
    val corner: Float,
    val look: GlassLook,
    val aperture: Float = 0f,
    val chain: GlassChain? = null,
    /** A rounded box's left and right sides' bow (px, + out, − in). */
    val bendLeft: Float = 0f,
    val bendRight: Float = 0f,
)

internal class GlassEntry(look: GlassLook, val bornAt: Long) {
    var look by mutableStateOf(look)
    var coords: LayoutCoordinates? = null
    var goneAt = 0L
    var lastRect: Rect? = null

    /** Where it was last placed on screen (the surface redraws only when it moves). */
    var placedAt: Rect? = null
}

/** One liquid glass surface per screen. Create with [rememberGlassScene]. */
@Stable
class GlassScene internal constructor(internal val backdrop: GraphicsLayer) {
    internal val entries = mutableStateMapOf<Any, GlassEntry>()
    internal var surface: LayoutCoordinates? = null

    /** Bumped when a glass element moves, so the surface redraws. */
    internal var moves by mutableIntStateOf(0)

    /** Bumped when glass elements come or go, to wake the droplet animation. */
    internal var version by mutableIntStateOf(0)

    /** Frame time while a droplet animation runs. */
    internal var frameNanos by mutableLongStateOf(0L)

    private fun animating(now: Long): Boolean = entries.values.any {
        now - it.bornAt < APPEAR_NS || it.goneAt != 0L
    }

    internal suspend fun runAnimations() {
        while (true) {
            val now = withFrameNanos { it }
            frameNanos = now
            val done = entries.filterValues { it.goneAt != 0L && now - it.goneAt > VANISH_NS }.keys.toList()
            done.forEach { entries.remove(it) }
            if (!animating(now)) {
                // Nothing moving: sleep until a glass element comes or goes.
                val seen = version
                snapshotFlow { version }.first { it != seen }
            }
        }
    }
}

@Composable
fun rememberGlassScene(): GlassScene {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassScene(layer) }
}

/** Where [coords] lies on this scene's glass surface (its pixels), if both are on screen. */
fun GlassScene.boundsOf(coords: LayoutCoordinates): Rect? {
    val s = surface ?: return null
    if (!s.isAttached || !coords.isAttached) return null
    return s.localBoundingBoxOf(coords, clipBounds = false)
}

/** Whether the watch face is round (Galaxy Watches are). */
@Composable
fun isRoundScreen(): Boolean = LocalConfiguration.current.isScreenRound

/**
 * Marks this element's content as the screen's backdrop: it isn't drawn directly, but through
 * the [GlassSurface], which bends it under the glass.
 */
fun Modifier.glassBackdrop(scene: GlassScene): Modifier =
    // In a layer of its own, so it's recorded again only when something in it changes (not
    // whenever something beside it redraws), and the glass is only redrawn for that.
    graphicsLayer {}.drawWithContent {
        scene.backdrop.record { this@drawWithContent.drawContent() }
    }

/**
 * Makes this element a piece of the screen's liquid glass. The glass is drawn by the
 * [GlassSurface] (which must come before this element, so the element's own content sits on
 * top of it); it grows in like a droplet when the element appears and shrinks away when it
 * leaves.
 */
@Composable
fun Modifier.glass(scene: GlassScene, look: GlassLook = GlassLooks.Pill): Modifier {
    val key = remember { Any() }
    DisposableEffect(scene, key) {
        val entry = GlassEntry(look, System.nanoTime())
        scene.entries[key] = entry
        scene.version++
        onDispose {
            entry.goneAt = System.nanoTime()
            scene.version++
        }
    }
    SideEffect { scene.entries[key]?.look = look }
    return this.onGloballyPositioned { coords ->
        scene.entries[key]?.let {
            it.coords = coords
            val placed = coords.boundsInRoot()
            if (placed != it.placedAt) {
                it.placedAt = placed
                scene.moves++
            }
        }
    }
}

/**
 * Draws the screen's backdrop through the liquid glass: the domed crystal over the whole face,
 * plus every glass shape (from Modifier.glass, and from [shapes] if given).
 *
 * @param merge how close (dp) two pieces of glass must come before they melt into one, read
 *        at every frame (0 = they never fuse); default [Tuning.GLASS_MERGE_DP].
 */
@Composable
fun GlassSurface(
    scene: GlassScene,
    modifier: Modifier = Modifier,
    merge: (() -> Float)? = null,
    shapes: (DrawScope.() -> List<GlassShape>)? = null,
) {
    val round = isRoundScreen()
    val layer = rememberGraphicsLayer()
    val engine = remember {
        if (Build.VERSION.SDK_INT >= 33 && GlassSupport.shaderWorks) {
            try {
                GlassShaderEngine()
            } catch (t: Throwable) {
                Log.w(TAG, "Glass shader unavailable, using the simple look", t)
                GlassSupport.shaderWorks = false
                null
            }
        } else {
            null
        }
    }
    LaunchedEffect(scene) { scene.runAnimations() }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { scene.surface = it }
            // A layer of its own: the glass is worked out and drawn again only when it (or its
            // backdrop) changes, not whenever something else on the screen redraws.
            .graphicsLayer {}
            .drawBehind {
                val list = resolveShapes(scene, shapes)
                val mergeDp = (merge?.invoke() ?: Tuning.GLASS_MERGE_DP).coerceAtLeast(0f)
                if (engine != null && GlassSupport.shaderWorks && Build.VERSION.SDK_INT >= 33) {
                    try {
                        layer.record { drawLayer(scene.backdrop) }
                        layer.renderEffect = engine.effect(this, round, list, mergeDp)
                        drawLayer(layer)
                        return@drawBehind
                    } catch (t: Throwable) {
                        Log.w(TAG, "Glass shader failed, switching to the simple look", t)
                        GlassSupport.shaderWorks = false
                    }
                }
                drawLayer(scene.backdrop)
                list.forEach { simpleGlass(it) }
            },
    )
}

/** Collects the scene's shapes in the surface's pixels, with the droplet animation applied. */
private fun DrawScope.resolveShapes(scene: GlassScene, computed: (DrawScope.() -> List<GlassShape>)?): List<Resolved> {
    // Reading these makes the surface redraw when elements move, come, go or animate.
    scene.moves
    scene.version
    val now = scene.frameNanos
    val out = ArrayList<Resolved>(MAX_SHAPES)
    val surface = scene.surface
    for (entry in scene.entries.values) {
        val coords = entry.coords
        val rect = if (surface != null && surface.isAttached && coords != null && coords.isAttached && entry.goneAt == 0L) {
            surface.localBoundingBoxOf(coords, clipBounds = false).also { entry.lastRect = it }
        } else {
            entry.lastRect
        } ?: continue
        val scale = when {
            entry.goneAt != 0L -> 1f - easeIn(((now - entry.goneAt).toFloat() / VANISH_NS).coerceIn(0f, 1f))
            else -> springIn((now - entry.bornAt).toFloat() / 1_000_000_000f)
        }
        if (scale <= 0.01f) continue
        out += resolve(rect, entry.look, scale)
        if (out.size == MAX_SHAPES) return out
    }
    computed?.invoke(this)?.forEach {
        if (out.size == MAX_SHAPES) return out
        val arc = it.arc
        val chain = it.chain
        out += when {
            arc != null -> Resolved(arc.center.x, arc.center.y, arc.radius, arc.halfThickness, arc.middle, it.look, arc.halfAperture.coerceAtLeast(0.0001f))
            chain != null -> Resolved(chain.center.x, chain.center.y, chain.radius, chain.beadRadius, chain.firstAngle, it.look, -1f, chain)
            else -> resolve(it.rect, it.look, 1f, it.bendLeft, it.bendRight)
        }
    }
    return out
}

private fun DrawScope.resolve(rect: Rect, look: GlassLook, scale: Float, bendLeft: Float = 0f, bendRight: Float = 0f): Resolved {
    val hw = rect.width / 2f * scale
    val hh = rect.height / 2f * scale
    val corner = look.corner?.toPx()?.times(scale)?.coerceAtMost(min(hw, hh)) ?: min(hw, hh)
    return Resolved(rect.center.x, rect.center.y, hw, hh, corner, look, bendLeft = bendLeft * scale, bendRight = bendRight * scale)
}

/**
 * The spring all moving liquid glass follows (the Rate page droplet's; see
 * [Tuning.GLASS_SPRING_DAMPING]).
 */
fun <T> glassSpring(visibilityThreshold: T? = null): SpringSpec<T> = spring(
    dampingRatio = Tuning.GLASS_SPRING_DAMPING,
    stiffness = Tuning.GLASS_SPRING_STIFFNESS,
    visibilityThreshold = visibilityThreshold,
)

/** How far a piece of glass has grown [seconds] after it appeared: it springs in on [glassSpring]. */
private fun springIn(seconds: Float): Float {
    if (seconds <= 0f) return 0f
    val w = sqrt(Tuning.GLASS_SPRING_STIFFNESS)
    val z = Tuning.GLASS_SPRING_DAMPING.coerceIn(0.05f, 0.999f)
    val wd = w * sqrt(1f - z * z)
    val decay = exp(-z * w * seconds)
    return 1f - decay * (cos(wd * seconds) + z * w / wd * sin(wd * seconds))
}

private fun easeIn(t: Float): Float = t * t

/** Whether the GPU glass shader is usable on this watch (set to false if it ever fails). */
internal object GlassSupport {
    @Volatile
    var shaderWorks: Boolean = Build.VERSION.SDK_INT >= 33
}

@RequiresApi(33)
private class GlassShaderEngine {
    private val shader = RuntimeShader(GLASS_SHADER)
    private val rect = FloatArray(MAX_SHAPES * 4)
    private val shape = FloatArray(MAX_SHAPES * 4)
    private val lens = FloatArray(MAX_SHAPES * 4)
    private val tintA = FloatArray(MAX_SHAPES * 4)
    private val tintB = FloatArray(MAX_SHAPES * 4)
    private val chains = FloatArray(MAX_SHAPES * 4)

    fun effect(scope: DrawScope, round: Boolean, shapes: List<Resolved>, mergeDp: Float): androidx.compose.ui.graphics.RenderEffect {
        rect.fill(0f)
        shape.fill(0f)
        lens.fill(0f)
        tintA.fill(0f)
        tintB.fill(0f)
        chains.fill(0f)
        val bend = Tuning.GLASS_BEND
        with(scope) {
            shapes.forEachIndexed { i, s ->
                val o = i * 4
                rect[o] = s.cx
                rect[o + 1] = s.cy
                rect[o + 2] = s.hw
                rect[o + 3] = s.hh
                shape[o] = s.corner
                shape[o + 1] = s.look.tintAmount
                shape[o + 2] = s.look.frost.toPx()
                shape[o + 3] = s.look.zoom
                lens[o] = s.look.rim.toPx()
                lens[o + 1] = s.look.refraction.toPx() * bend
                lens[o + 2] = s.look.fringe.toPx() * bend
                lens[o + 3] = (s.look.merge?.toPx() ?: (mergeDp * density)).coerceAtLeast(0f)
                tintA[o] = s.look.tint.red
                tintA[o + 1] = s.look.tint.green
                tintA[o + 2] = s.look.tint.blue
                tintA[o + 3] = s.aperture // 0 = a rounded box
                tintB[o] = s.look.tintEnd.red
                tintB[o + 1] = s.look.tintEnd.green
                tintB[o + 2] = s.look.tintEnd.blue
                tintB[o + 3] = when {
                    s.look.lens -> 2f
                    s.look.over -> 1f
                    else -> 0f
                }
                val c = s.chain
                if (c != null) {
                    chains[o] = c.start
                    chains[o + 1] = c.count
                    chains[o + 2] = c.innerMerge
                    chains[o + 3] = c.step
                } else if (s.aperture == 0f) {
                    chains[o] = s.bendLeft // a rounded box's bowed sides
                    chains[o + 1] = s.bendRight
                }
            }
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uDensity", density)
            shader.setFloatUniform("uMerge", mergeDp * density)
        }
        shader.setFloatUniform("uLight", Tuning.GLASS_LIGHT)
        shader.setFloatUniform("uShadow", Tuning.GLASS_SHADOW)
        shader.setFloatUniform("uRound", if (round) 1f else 0f)
        shader.setFloatUniform("uScreen", Tuning.GLASS_SCREEN)
        shader.setFloatUniform("uCount", shapes.size.toFloat())
        shader.setFloatUniform("uLenses", if (shapes.any { it.look.lens }) 1f else 0f)
        shader.setFloatUniform("uRect", rect)
        shader.setFloatUniform("uShape", shape)
        shader.setFloatUniform("uLens", lens)
        shader.setFloatUniform("uTintA", tintA)
        shader.setFloatUniform("uTintB", tintB)
        shader.setFloatUniform("uChain", chains)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

// ---- The simple look (no shader) ------------------------------------------------------------

/** Without the shader: each glass shape as a see-through panel with a thin light edge. */
private fun DrawScope.simpleGlass(s: Resolved) {
    val chain = s.chain
    if (chain != null) {
        // A chain of beads: a see-through dot per bead.
        val color = s.look.tintEnd.copy(alpha = (0.10f + 0.8f * s.look.tintAmount).coerceAtMost(1f))
        val full = kotlin.math.floor(chain.count).toInt()
        for (k in 0..full) {
            val scale = if (k < full) 1f else chain.count - full
            if (scale < 0.02f) continue
            val a = chain.firstAngle + (chain.start + k) * chain.step
            val c = Offset(chain.center.x + chain.radius * kotlin.math.cos(a), chain.center.y + chain.radius * kotlin.math.sin(a))
            drawCircle(color, radius = chain.beadRadius * scale, center = c)
        }
        return
    }
    if (s.aperture > 0f) {
        // An arc: a see-through stroke along the ring.
        val degrees = 180f / PI.toFloat()
        drawArc(
            color = s.look.tintEnd.copy(alpha = (0.10f + 0.8f * s.look.tintAmount).coerceAtMost(1f)),
            startAngle = (s.corner - s.aperture) * degrees,
            sweepAngle = 2f * s.aperture * degrees,
            useCenter = false,
            topLeft = Offset(s.cx - s.hw, s.cy - s.hw),
            size = Size(2f * s.hw, 2f * s.hw),
            style = Stroke(width = 2f * s.hh, cap = StrokeCap.Round),
        )
        return
    }
    val left = s.cx - s.hw
    val right = s.cx + s.hw
    val top = s.cy - s.hh
    val bottom = s.cy + s.hh
    val path = Path().apply {
        addRoundRect(RoundRect(left, top, right, bottom, CornerRadius(s.corner)))
    }
    val tintA = s.look.tint.copy(alpha = (0.10f + 0.8f * s.look.tintAmount).coerceAtMost(1f))
    val tintB = s.look.tintEnd.copy(alpha = (0.06f + 0.8f * s.look.tintAmount).coerceAtMost(1f))
    drawPath(path, Brush.horizontalGradient(listOf(tintA, tintB), startX = left, endX = right))
    val light = Tuning.GLASS_LIGHT
    if (light > 0f) {
        val edge = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = (0.45f * light).coerceAtMost(1f)),
                Color.White.copy(alpha = (0.08f * light).coerceAtMost(1f)),
                Color.White.copy(alpha = (0.22f * light).coerceAtMost(1f)),
            ),
            startY = top,
            endY = bottom,
        )
        drawPath(path, edge, style = Stroke(width = 1.dp.toPx()))
    }
}

// ---- The shader -----------------------------------------------------------------------------
// Checked by compiling and rendering it with Skia (the engine behind Android's RuntimeShader).

private const val GLASS_SHADER = """
// Liquid glass, in one pass over the whole screen.
//
// `content` is everything behind the glass. The pass redraws it through a slightly domed lens
// (the screen-wide glass sheet), then draws every glass shape as part of one liquid surface:
// shapes fuse through a smooth liquid neck where they come close. Each shape is a thick slab
// of glass with a rounded rim. The rim gathers in what lies beyond the edge and wraps it
// around the inside of the edge, so lines behind the glass bend into smooth contours that
// follow its outline; red and blue bend by slightly different amounts, leaving a faint rainbow
// along the bottom. Light catches the rim: a soft bright band just inside the edge, a
// hair-thin highlight on the edge itself and a faint darker outline. The body can be frosted,
// magnified and tinted, with a gentle gradient, and a soft shadow lifts it off the page.
uniform shader content;
uniform float2 uSize;       // area size, px
uniform float uDensity;     // px per dp
uniform float uRound;       // 1 = round screen
uniform float uScreen;      // strength of the screen-wide glass, 0..1
uniform float uMerge;       // the surface's usual fusing reach, px (softens how looks blend)
uniform float uLight;       // strength of the light on the rims, 0..1
uniform float uShadow;      // strength of the soft shadow, 0..1
uniform float uCount;       // number of glass shapes in use
uniform float uLenses;      // 1 if any shape is a lens with its own outline (see lensField)
uniform float4 uRect[16];   // shape: centre x, centre y, half width, half height (px)
                            //        (an arc: ring centre x, y, ring radius, half thickness;
                            //        a chain of beads: ring centre x, y, ring radius, bead radius)
uniform float4 uShape[16];  // shape: corner radius (px; an arc: the direction of its middle, a chain:
                            //        the angle of its bead 0, radians),
                            //        tint amount 0..1, frost radius (px), zoom (1 = none)
uniform float4 uLens[16];   // shape: rim width (px), refraction (px), rainbow spread (px),
                            //        how far it reaches to fuse with the shapes before it (px, 0 = never)
uniform float4 uTintA[16];  // shape: tint colour at its left edge (an arc's: at one end); alpha = 0 for a
                            //        rounded box, an arc's half opening (radians) for a curved tube,
                            //        or -1 for a chain of beads
uniform float4 uTintB[16];  // shape: tint colour at its right edge; alpha = 1 if the shape's look
                            //        should cover the looks of the shapes under it, 2 if it is
                            //        also a lens with its own outline (see lensField)
uniform float4 uChain[16];  // a chain of beads: first bead, how many (the last one may be partly
                            //        grown), how far beads reach to melt together (px), angle between beads;
                            //        a rounded box: how far its left and right sides bow (px, + out, - in)

const int MAX = 16;
const int FROST_TAPS = 16;

float sdBox(float2 p, float2 b, float r) {
    r = min(r, min(b.x, b.y));
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// Smooth minimum (cubic): two shapes closer than k melt into one through a rounded neck.
float smin(float a, float b, float k) {
    if (k < 0.01) return min(a, b);
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * h * k * (1.0 / 6.0);
}

// A chain of beads around r.xy on a ring of radius r.z, each r.w big: bead n sits at angle
// first + n * ch.w. Beads ch.x .. ch.x + ch.y are there (the last one grown by the fraction of
// ch.y), melting together where closer than ch.z. Only the four beads nearest p are checked.
float sdChain(float2 p, float4 r, float first, float4 ch) {
    float2 q = p - r.xy;
    float step = ch.w;
    float t = (atan(q.y, q.x) - first) / step;
    float period = 6.2831853 / abs(step);
    t -= period * floor((t - (ch.x + ch.y * 0.5)) / period + 0.5); // the turn nearest the chain
    float k0 = floor(t);
    float lastFull = ch.x + floor(ch.y) - 1.0;
    float d = 100000.0;
    // One sin/cos for the first of the four nearest beads, then a turn of [step] per bead.
    float a0 = first + (k0 - 1.0) * step;
    float2 dir = float2(cos(a0), sin(a0));
    float2 turn = float2(cos(step), sin(step));
    for (int j = 0; j < 4; j++) {
        float idx = k0 + float(j) - 1.0;
        float scale = idx > lastFull + 0.01 ? fract(ch.y) : 1.0;
        if (idx > ch.x - 0.01 && idx < lastFull + 1.01 && scale > 0.02) {
            d = smin(d, length(q - r.z * dir) - r.w * scale, ch.z);
        }
        dir = float2(dir.x * turn.x - dir.y * turn.y, dir.x * turn.y + dir.y * turn.x);
    }
    return d;
}

// A piece of glass: a rounded box, its left and right sides bowed out (+) or in (-) by chain.x
// and chain.y px at their middle (straight at the corners); or (when [aperture] > 0) an arc: a
// curved tube with round ends along a ring around r.xy of radius r.z, r.w thick on each side,
// opening [aperture] radians each way from the direction [corner]; or (when [aperture] < 0) a
// chain of beads (see sdChain) starting at the angle [corner].
float sdShape(float2 p, float4 r, float corner, float aperture, float4 chain) {
    if (aperture < 0.0) return sdChain(p, r, corner, chain);
    if (aperture > 0.0) {
        float2 q = p - r.xy;
        float2 lq = float2(q.x * sin(corner) - q.y * cos(corner), q.x * cos(corner) + q.y * sin(corner));
        lq.x = abs(lq.x);
        float2 sc = float2(sin(aperture), cos(aperture));
        float d = (sc.y * lq.x > sc.x * lq.y) ? length(lq - sc * r.z) : abs(length(lq) - r.z);
        return d - r.w;
    }
    float2 q = p - r.xy;
    float2 hb = r.zw;
    if (chain.x != 0.0 || chain.y != 0.0) {
        float t = clamp(q.y / max(r.w, 1.0), -1.0, 1.0);
        float side = mix(chain.x, chain.y, smoothstep(-0.3 * r.z, 0.3 * r.z, q.x));
        hb.x += side * (1.0 - t * t);
    }
    return sdBox(q, hb, corner);
}

// Signed distance to the liquid surface (negative inside).
float field(float2 p) {
    float d = 100000.0;
    for (int i = 0; i < MAX; i++) {
        if (float(i) >= uCount) break;
        float4 r = uRect[i];
        d = smin(d, sdShape(p, r, uShape[i].x, uTintA[i].a, uChain[i]), uLens[i].w);
    }
    return d;
}

// The lenses: shapes laid over other glass that keep their own outline (the volume knob in
// its pill). Where one lies, the glass takes its shape from the lens alone, so it shows its own
// rim, light and bend even deep inside the glass around it.
float lensField(float2 p) {
    float d = 100000.0;
    for (int i = 0; i < MAX; i++) {
        if (float(i) >= uCount) break;
        if (uTintB[i].a > 1.5) d = min(d, sdShape(p, uRect[i], uShape[i].x, uTintA[i].a, uChain[i]));
    }
    return d;
}

float3 spectrum(float h) {
    return clamp(abs(fract(h + float3(0.0, 0.6667, 0.3333)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

half4 main(float2 p) {
    float dp = uDensity;
    float2 c = uSize * 0.5;
    float R = min(uSize.x, uSize.y) * 0.5;

    // ---- The glass sheet over the whole face: a slightly domed lens ----------------------
    float2 v = p - c;
    float rr = length(v);
    float2 radial = rr > 0.001 ? v / rr : float2(0.0, 0.0);
    float2 q = p;
    if (uScreen > 0.0) {
        float rimS = 18.0 * dp;
        float dEdge = uRound > 0.5 ? R - rr : min(min(p.x, uSize.x - p.x), min(p.y, uSize.y - p.y));
        float edgeT = clamp(dEdge / rimS, 0.0, 1.0);
        float bend = (1.0 - edgeT) * (1.0 - edgeT) * rimS * 0.55 * uScreen;
        q = p - radial * bend; // near the rim the picture is stretched outward
    }
    float4 base = float4(content.eval(q));
    if (uCount < 0.5) return half4(base);

    float dGlass = field(p);
    float shadowReach = 16.0 * dp;
    if (dGlass > shadowReach) return half4(base);

    // ---- Soft shadow under the glass, a little below it -------------------------------------
    if (uShadow > 0.0) {
        float ds = field(p - float2(0.0, 4.0 * dp));
        float s = 1.0 - smoothstep(-8.0 * dp, 14.0 * dp, ds);
        base.rgb *= 1.0 - uShadow * 0.22 * s * s;
    }
    if (dGlass > 1.0) return half4(base);

    // ---- Inside the glass ------------------------------------------------------------------
    // Inside a lens, its own outline shapes the glass; elsewhere the liquid surface does.
    float dLens = uLenses > 0.5 ? lensField(p) : 100000.0;
    bool inLens = dLens < 0.0;
    float d = inLens ? dLens : dGlass;
    // Surface normal from the distance field (points outward).
    // Where shapes fuse, the field flattens out inside the neck, so its slope also gives a
    // truer depth there (and keeps the very middle of a neck calm).
    float2 grad;
    if (inLens) {
        grad = float2(lensField(p + float2(1.0, 0.0)) - lensField(p - float2(1.0, 0.0)),
                      lensField(p + float2(0.0, 1.0)) - lensField(p - float2(0.0, 1.0))) * 0.5;
    } else {
        grad = float2(field(p + float2(1.0, 0.0)) - field(p - float2(1.0, 0.0)),
                      field(p + float2(0.0, 1.0)) - field(p - float2(0.0, 1.0))) * 0.5;
    }
    float slopeLen = length(grad);
    float2 n = grad / max(slopeLen, 0.0001);

    // Blend each shape's look by closeness, so fused shapes melt into one another.
    float wsum = 0.0;
    float tintW = 0.0;
    float3 tint = float3(0.0, 0.0, 0.0);
    float frost = 0.0;
    float zoom = 0.0;
    float rimW = 0.0;
    float refr = 0.0;
    float fringe = 0.0;
    float2 zc = float2(0.0, 0.0);
    float2 hs = float2(0.0, 0.0);
    float reach = max(uMerge * 0.35, 1.5 * dp);
    for (int i = 0; i < MAX; i++) {
        if (float(i) >= uCount) break;
        float4 r = uRect[i];
        float4 s = uShape[i];
        float4 l = uLens[i];
        float ap = uTintA[i].a;
        float di = sdShape(p, r, s.x, ap, uChain[i]);
        float w = exp(-max(di, 0.0) / reach);
        // A lens laid over other glass (the rating droplet) shows its own look where it lies.
        w *= 1.0 + 30.0 * min(uTintB[i].a, 1.0) * smoothstep(1.0 * dp, -3.0 * dp, di);
        float u = clamp((p.x - (r.x - r.z)) / max(2.0 * r.z, 1.0), 0.0, 1.0);
        float2 cen = r.xy;
        float2 halfSize = r.zw;
        float rimCap = min(r.z, r.w);
        if (ap < 0.0) {
            // A chain of beads: its "middle" is the nearest point of the ring.
            float2 q = p - r.xy;
            cen = r.xy + q / max(length(q), 0.0001) * r.z;
            halfSize = float2(r.w, r.w);
            rimCap = r.w;
            u = 0.5;
        } else if (ap > 0.0) {
            // An arc: its tint runs from one end to the other, and its "middle" is the nearest
            // point of the ring, so the body gradient runs across the tube.
            float2 q = p - r.xy;
            float2 lq = float2(q.x * sin(s.x) - q.y * cos(s.x), q.x * cos(s.x) + q.y * sin(s.x));
            u = clamp(0.5 + 0.5 * atan(lq.x, max(lq.y, 0.0001)) / ap, 0.0, 1.0);
            cen = r.xy + q / max(length(q), 0.0001) * r.z;
            halfSize = float2(r.w, r.w);
            rimCap = r.w;
        }
        tint += w * s.y * mix(uTintA[i].rgb, uTintB[i].rgb, u);
        tintW += w * s.y;
        frost += w * s.z;
        zoom += w * s.w;
        rimW += w * min(l.x, rimCap * 0.9); // the rim can't be wider than the shape
        refr += w * l.y;
        fringe += w * l.z;
        zc += w * cen;
        hs += w * halfSize;
        wsum += w;
    }
    wsum = max(wsum, 0.0001);
    tint = tint / max(tintW, 0.0001);
    float tintAmount = tintW / wsum;
    frost /= wsum;
    zoom = max(zoom / wsum, 0.5);
    rimW = max(rimW / wsum, 1.0);
    refr /= wsum;
    fringe /= wsum;
    zc /= wsum;
    hs /= wsum;

    // The rounded rim, as a lens. Across the rim the view reaches further and further out
    // past the edge, smoothly from nothing (where the glass turns flat) to `refr` at the edge,
    // so what lies around the glass is gathered into the rim and wraps along its outline.
    float depth = -d / clamp(slopeLen, 0.05, 1.0);
    float x = clamp(depth / rimW, 0.0, 1.0);    // 0 at the edge, 1 where the glass is flat
    float f = 1.0 - x * x * (3.0 - 2.0 * x);
    float squeeze = refr * 6.0 * x * (1.0 - x) / rimW; // how tightly the view is packed here

    float2 sp = zc + (q - zc) / zoom;       // magnify around the middle (if asked)
    sp += n * f * refr;

    // Frost where asked, plus a little softness where the view is packed tight, so it stays
    // smooth instead of shimmering.
    float soft = frost + min(squeeze * 0.35, 3.0 * dp);
    float4 col;
    if (soft > 0.75) {
        col = float4(0.0);
        for (int k = 0; k < FROST_TAPS; k++) {
            float t = (float(k) + 0.5) / float(FROST_TAPS);
            float a = float(k) * 2.3999632;   // golden angle: an even spread of taps
            col += float4(content.eval(sp + float2(cos(a), sin(a)) * sqrt(t) * soft));
        }
        col /= float(FROST_TAPS);
    } else {
        col = float4(content.eval(sp));
    }

    // Rainbow in the rim: red bends a little less, blue a little more. Strongest along the
    // bottom, where the light leaves the glass.
    float bottom = max(n.y, 0.0);
    float ca = fringe * f * (0.35 + 0.65 * bottom);
    if (ca > 0.05) {
        float k = clamp(f * 1.4, 0.0, 1.0);
        col.r = mix(col.r, float(content.eval(sp - n * ca).r), k);
        col.b = mix(col.b, float(content.eval(sp + n * ca).b), k);
    }

    col.rgb = mix(col.rgb, tint, tintAmount);

    // A gentle gradient through the body: a touch lighter toward the top left.
    float2 rel = clamp((p - zc) / max(hs, float2(1.0)), -1.0, 1.0);
    float g = dot(rel, float2(-0.40, -0.92));              // +1 at the top left
    col.rgb += (1.0 - col.rgb) * (0.03 + 0.025 * g) - col.rgb * 0.025 * max(-g, 0.0);

    // Light on the rim. Light comes from the top left and, bounced inside the glass, leaves
    // again at the bottom right; the sides in between stay almost unlit.
    float2 L = float2(-0.6, -0.8);
    float facing = dot(n, L);
    float toward = max(facing, 0.0);
    float away = max(-facing, 0.0);
    float bandW = max(rimW * 0.6, 2.0 * dp);
    float band = 1.0 - clamp(depth / bandW, 0.0, 1.0);
    band = band * band;                                              // soft glow inside the edge
    float hair = exp(-pow((depth - 0.9 * dp) / (0.45 * dp), 2.0));   // the thin bright edge
    float outline = 1.0 - smoothstep(0.0, 0.8 * dp, depth);          // faint darker outline
    float glowAmt = 0.12 * band * (0.25 + 0.75 * toward * toward);
    float hairAmt = hair * (0.05 + 0.55 * toward * toward + 0.25 * away * away);
    float3 light = float3(glowAmt + hairAmt);
    // The curved rim has volume: lighter where it turns toward the light, darker where it
    // turns away.
    col.rgb *= 1.0 - uLight * 0.10 * f * away;
    // A short stretch of the bottom edge breaks the light into colour.
    float u = (p.x - zc.x) / max(hs.x, 1.0);              // -1 at the left end, 1 at the right
    float stretch = exp(-pow((u + 0.15) / 0.28, 2.0));
    light += spectrum(fract(0.12 + u * 1.2)) * hair * pow(bottom, 6.0) * stretch * 0.20;
    col.rgb += uLight * light * (1.0 - col.rgb * 0.6);
    col.rgb *= 1.0 - uLight * 0.20 * outline;

    float a = smoothstep(1.0, -1.0, dGlass);                   // crisp, antialiased edge
    return half4(mix(base, float4(clamp(col.rgb, 0.0, 1.0), base.a), a));
}
"""
