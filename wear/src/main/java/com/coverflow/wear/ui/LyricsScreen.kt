package com.coverflow.wear.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.coverflow.shared.Lrc
import com.coverflow.shared.LyricLine
import com.coverflow.shared.Lyrics
import com.coverflow.shared.PlayerState
import com.coverflow.shared.Track
import com.coverflow.wear.Tuning
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lyrics of the playing song, over its blurred cover. Synced lyrics scroll with the song,
 * keeping the current line in the middle of the screen: it's sharp and bright, the lines next to
 * it are sharp but dimmer, and the rest are blurred and dimmed, so lines ease in and out of
 * focus. The bezel (or a drag) scrolls by hand for a few seconds. The only liquid glass is the
 * clock's pill, as on the other pages.
 *
 * Back: 1 = back, 2 = rate the song, 3 = Now Playing.
 */
@Composable
fun LyricsScreen(
    state: PlayerState?,
    lyrics: Map<String, Lyrics>,
    art: Map<String, ImageBitmap>,
    active: Boolean,
    actions: ScreenActions,
    haptics: Haptics,
    requestLyrics: (Track) -> Unit,
    onRate: () -> Unit,
    requestArt: (String) -> Unit = {},
) {
    val current = state?.current
    LaunchedEffect(current?.songKey) { current?.let(requestLyrics) }

    val rateLatest by rememberUpdatedState(onRate)
    SideEffect {
        if (active) actions.onPress = { presses ->
            when (presses) {
                2 -> {
                    rateLatest()
                    true
                }
                4 -> true // already here
                else -> false
            }
        }
    }

    val data = current?.let { lyrics[it.songKey] }
    val backdropTrack = current ?: state?.list?.firstOrNull() // nothing playing: the last song
    LaunchedEffect(backdropTrack?.artKey) { backdropTrack?.artKey?.let(requestArt) }
    val backdrop = Backdrop(backdropTrack, backdropTrack?.let { art[it.artKey] })

    // The only glass here is the clock's pill (as on the other pages): the page is its backdrop.
    val scene = rememberGlassScene()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassBackdrop(scene)) {
            when {
                state == null || current == null -> LyricsMessage(backdrop, "Nothing playing")
                data == null -> LyricsMessage(backdrop, "Finding lyrics…", busy = true)
                data.instrumental -> LyricsMessage(backdrop, "♪  Instrumental")
                !data.found -> LyricsMessage(backdrop, data.note.ifBlank { "No lyrics found for this song" })
                data.synced.isNotEmpty() -> SyncedLyrics(data.synced, state, active, haptics, backdrop)
                else -> PlainLyrics(data.plain, active, haptics, backdrop)
            }
        }
        GlassSurface(scene)
        GlassClock(scene)
    }
}

/** What the lyrics page's background is made of: the song's cover. */
private class Backdrop(val track: Track?, val art: ImageBitmap?)

/** The lyrics page's background (the cover blurred, as on Now Playing) under [content]. */
@Composable
private fun LyricsBackdrop(backdrop: Backdrop, content: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize()) {
        BlurredCover(backdrop.track, backdrop.art)
        content()
    }
}

@Composable
private fun LyricsMessage(backdrop: Backdrop, text: String, busy: Boolean = false) {
    LyricsBackdrop(backdrop)
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(26.dp), indicatorColor = Color.White)
            Text(text, fontSize = 14.sp, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    state: PlayerState,
    active: Boolean,
    haptics: Haptics,
    backdrop: Backdrop,
) {
    // Move on to each line right as it starts: wake exactly then (and at least every quarter
    // second, to catch seeks and pauses).
    val stateLatest by rememberUpdatedState(state)
    val now = produceState(System.currentTimeMillis(), lines) {
        while (true) {
            val t = System.currentTimeMillis()
            value = t
            val s = stateLatest
            val pos = s.positionAt(t) + Tuning.LYRICS_LEAD_MS
            val next = lines.getOrNull(Lrc.indexAt(lines, pos) + 1)?.timeMs
            val untilNext = if (s.isPlaying && next != null && s.speed > 0f) ((next - pos) / s.speed).toLong() + 2L else LINE_CHECK_MS
            delay(untilNext.coerceIn(4L, LINE_CHECK_MS))
        }
    }
    // Only a change of line rebuilds the lyrics (not every tick of the clock).
    val currentLine by remember(lines) {
        derivedStateOf { Lrc.indexAt(lines, stateLatest.positionAt(now.value) + Tuning.LYRICS_LEAD_MS) }
    }

    val scroll = rememberScrollState()
    val tops = remember(lines) { IntArray(lines.size) }
    val heights = remember(lines) { IntArray(lines.size) }
    var manualUntil by remember { mutableLongStateOf(0L) }
    // Follows the song again once the hold after a scroll runs out (timed: nothing else redraws then).
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(manualUntil) {
        val wait = manualUntil - SystemClock.uptimeMillis()
        following = wait <= 0L
        if (wait > 0L) {
            delay(wait)
            following = true
        }
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val bezelStepPx = with(density) { 42.dp.toPx() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .rotarySteps(active) { steps ->
                manualUntil = SystemClock.uptimeMillis() + Tuning.LYRICS_MANUAL_HOLD_MS
                haptics.tick()
                scope.launch { scroll.animateScrollBy(steps * bezelStepPx, glassSpring()) }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, amount ->
                    change.consume()
                    manualUntil = SystemClock.uptimeMillis() + Tuning.LYRICS_MANUAL_HOLD_MS
                    scroll.dispatchRawDelta(-amount)
                }
            },
    ) {
        val viewport = constraints.maxHeight
        val halfDp = with(density) { (viewport / 2).toDp() }

        // Keep the current line in the middle of the screen.
        LaunchedEffect(currentLine, following, viewport) {
            if (!following) return@LaunchedEffect
            withFrameNanos { } // let the lines lay out first
            val target = if (currentLine < 0) 0 else tops[currentLine] + heights[currentLine] / 2 - viewport / 2
            scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue), glassSpring())
        }

        LyricsBackdrop(backdrop) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll, enabled = false)
                    .padding(horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(halfDp))
                lines.forEachIndexed { i, line ->
                    LyricLineText(
                        text = line.text,
                        distance = abs(i - currentLine),
                        modifier = Modifier.onPlaced { coords ->
                            tops[i] = coords.positionInParent().y.roundToInt()
                            heights[i] = coords.size.height
                        },
                    )
                }
                Spacer(Modifier.height(halfDp))
            }
        }
    }
}

/** How big the sharp lines next to the current one are drawn, and the rest. */
private const val NEIGHBOR_SCALE = 0.94f
private const val DIM_SCALE = 0.9f

/** The longest the synced lyrics wait before checking the song's position again (ms). */
private const val LINE_CHECK_MS = 250L

/** How quickly a line lights up as it starts, and dims as it ends (ms). */
private const val LIGHT_UP_MS = 140
private const val DIM_DOWN_MS = 320

/**
 * The current line is sharp and bright. The lines right before and after it stay sharp but
 * dimmer, and the rest are blurred and dimmer still, so lines ease in and out of focus.
 *
 * @param distance how many lines away from the current one this line is (0 = current).
 */
@Composable
private fun LyricLineText(text: String, distance: Int, modifier: Modifier) {
    val isCurrent = distance == 0
    val isNeighbor = !isCurrent && distance <= Tuning.LYRICS_SHARP_NEIGHBORS
    val alpha by animateFloatAsState(
        when {
            isCurrent -> 1f
            isNeighbor -> Tuning.LYRICS_NEIGHBOR_ALPHA
            else -> Tuning.LYRICS_DIM_ALPHA
        },
        tween(if (isCurrent) LIGHT_UP_MS else DIM_DOWN_MS),
        label = "lineAlpha",
    )
    val scale by animateFloatAsState(
        when {
            isCurrent -> 1f
            isNeighbor -> NEIGHBOR_SCALE
            else -> DIM_SCALE
        },
        glassSpring(),
        label = "lineScale",
    )
    val blur by animateDpAsState(
        if (isCurrent || isNeighbor) 0.dp else Tuning.LYRICS_BLUR_DP.dp,
        tween(if (isCurrent || isNeighbor) LIGHT_UP_MS else DIM_DOWN_MS),
        label = "lineBlur",
    )
    Text(
        text = text.ifBlank { "♪" },
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        textAlign = TextAlign.Center,
        color = Color.White,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .blur(blur, BlurredEdgeTreatment.Unbounded),
    )
}

/** Lyrics without timings: plain scrolling text. */
@Composable
private fun PlainLyrics(text: String, active: Boolean, haptics: Haptics, backdrop: Backdrop) {
    val scroll: ScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { 42.dp.toPx() }
    LyricsBackdrop(backdrop) { PlainLyricsText(text, active, haptics, scroll, scope, stepPx) }
}

@Composable
private fun PlainLyricsText(
    text: String,
    active: Boolean,
    haptics: Haptics,
    scroll: ScrollState,
    scope: kotlinx.coroutines.CoroutineScope,
    stepPx: Float,
) {
    Column(
        Modifier
            .fillMaxSize()
            .rotarySteps(active) { steps ->
                haptics.tick()
                scope.launch { scroll.animateScrollBy(steps * stepPx) }
            }
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Not time-synced for this song",
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}
