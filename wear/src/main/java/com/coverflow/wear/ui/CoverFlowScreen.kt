package com.coverflow.wear.ui

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.coverflow.shared.CollectionKind
import com.coverflow.shared.CollectionResponse
import com.coverflow.shared.PlayerState
import com.coverflow.shared.Track
import com.coverflow.wear.Connection
import com.coverflow.wear.Tuning
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * iPod-style Cover Flow of the song list. The bezel moves through covers (one detent = one
 * cover), the highlighted cover's song, artist and album show underneath, the background is the
 * highlighted cover blurred, and a double Back press opens the cover.
 */
@Composable
fun CoverFlowScreen(
    state: PlayerState?,
    connection: Connection,
    art: Map<String, ImageBitmap>,
    active: Boolean,
    actions: ScreenActions,
    haptics: Haptics,
    requestArt: (String) -> Unit,
    prefetchArt: (List<Track>, Int) -> Unit,
    onEnter: (Track) -> Unit,
    /** A one-finger swipe up ([CollectionKind.LIKED]) or down ([CollectionKind.RECOMMENDED]). */
    onSwipe: (String) -> Unit = {},
) {
    // Which covers show: the player's own (its queue, or the songs up to the playing one), or the
    // recently played songs. Those come up when nothing is playing, and once the song has been
    // paused a while; a two-finger tap switches between the two, and that choice holds until the
    // music starts, stops, or has been paused a while.
    val stopped by heldFor(state != null && state.current == null, Tuning.STOPPED_AFTER_MS)
    val pausedLong by pausedLongerThan(state, Tuning.RECENT_AFTER_PAUSE_MS)
    val auto = stopped || pausedLong
    // What the music is doing (once it has held a moment, so a player briefly clearing the song
    // between tracks changes nothing), and which covers that brings up.
    val music by settled("${state == null}|$stopped|${state?.isPlaying == true}", Tuning.STOPPED_AFTER_MS)
    val phase = "$music|$auto"
    var choice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var choicePhase by rememberSaveable { mutableStateOf("") }
    val recentList = state?.recentlyPlayed.orEmpty()
    // Only with a song playing or paused are there two different sets of covers to switch between.
    val canSwitch = state != null && state.current != null && state.list.isNotEmpty() && recentList.isNotEmpty()
    val showRecent = recentList.isNotEmpty() && (choice?.takeIf { choicePhase == phase && canSwitch } ?: auto)

    fun switchCovers() {
        if (!canSwitch) {
            haptics.edge()
            return
        }
        choice = !showRecent
        choicePhase = phase
        haptics.strong()
    }
    val switchLatest by rememberUpdatedState<() -> Unit>({ switchCovers() })
    SideEffect { if (active) actions.onTwoFingerTap = { switchLatest() } }

    if (state == null || state.list.isEmpty()) {
        SideEffect { if (active) actions.onPress = { false } }
        StatusMessage(connection, hasState = state != null, player = state?.player.orEmpty())
        TimeText()
        return
    }

    // Arriving (the app opening, or coming back from another page): straight onto the right
    // covers. Switching while here: the covers dissolve into a blur and the others sharpen out of
    // it, the pane growing in again like a droplet.
    var shownRecent by remember { mutableStateOf(showRecent) }
    val switch = remember { Animatable(1f) }
    LaunchedEffect(showRecent) {
        if (shownRecent != showRecent) {
            switch.animateTo(0f, tween((SWITCH_OUT_MS * switch.value).roundToInt().coerceAtLeast(1), easing = LinearOutSlowInEasing))
            shownRecent = showRecent
        }
        switch.animateTo(1f, tween(SWITCH_IN_MS, easing = FastOutSlowInEasing))
    }
    val recent = shownRecent && recentList.isNotEmpty()
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val v = switch.value
                alpha = v
                val d = 1f - v
                renderEffect = if (Build.VERSION.SDK_INT >= 31 && d > 0.01f) {
                    val r = d * SWITCH_BLUR_DP.dp.toPx()
                    BlurEffect(r, r, TileMode.Decal)
                } else {
                    null
                }
            },
    ) {
        // Each set of covers keeps its own place (the other starts on the playing or newest song).
        key(recent) {
            CoverFlowContent(
                state = state,
                list = if (recent) recentList else state.list,
                recent = recent,
                art = art,
                active = active,
                actions = actions,
                haptics = haptics,
                requestArt = requestArt,
                prefetchArt = prefetchArt,
                onEnter = onEnter,
                swipeUp = SwipeAction("LIKED") { onSwipe(CollectionKind.LIKED) },
                swipeDown = SwipeAction("RECOMMENDED") { onSwipe(CollectionKind.RECOMMENDED) },
            )
        }
    }
}

/**
 * Songs recommended by the app that played last ([CollectionKind.RECOMMENDED], reached by
 * swiping down on Cover Flow) or liked in it ([CollectionKind.LIKED], swiping up), as a Cover
 * Flow of their own. Back ×1 opens the highlighted cover, Back ×2 (or swiping back the other
 * way) returns to the covers before.
 */
@Composable
fun CollectionScreen(
    kind: String,
    collection: CollectionResponse?,
    loading: Boolean,
    state: PlayerState?,
    art: Map<String, ImageBitmap>,
    active: Boolean,
    actions: ScreenActions,
    haptics: Haptics,
    requestArt: (String) -> Unit,
    prefetchArt: (List<Track>, Int) -> Unit,
    onEnter: (Track) -> Unit,
    onBack: () -> Unit,
) {
    val liked = kind == CollectionKind.LIKED
    val name = if (liked) "Liked" else "Recommended"
    // Swiping back the way it came returns, as Back ×2 does.
    val back = SwipeAction("BACK") { onBack() }
    val list = collection?.tracks.orEmpty()
    val app = collection?.app.orEmpty()
    if (list.isEmpty()) {
        val backLatest by rememberUpdatedState(onBack)
        SideEffect {
            if (active) {
                actions.onPress = { presses ->
                    if (presses == 2) backLatest()
                    presses <= 2
                }
            }
        }
        val pull = remember { Pull() }
        val upState = rememberUpdatedState(if (liked) null else back)
        val downState = rememberUpdatedState(if (liked) back else null)
        BoxWithConstraints(Modifier.fillMaxSize().verticalSwipes(pull, upState, downState, haptics)) {
            InfoCard(
                title = when {
                    loading -> "Finding ${name.lowercase()} songs…"
                    collection == null -> "No answer from the phone"
                    else -> "No ${name.lowercase()} songs"
                },
                body = when {
                    loading && app.isNotBlank() -> "From $app"
                    loading -> ""
                    collection == null -> "Check the phone is nearby, then swipe here again."
                    else -> collection.note
                },
                busy = loading,
            )
            SwipeHints(pull, upState.value, downState.value, constraints.maxHeight.toFloat())
        }
        TimeText()
        return
    }
    Box(Modifier.fillMaxSize()) {
        CoverFlowContent(
            state = state ?: PlayerState(),
            list = list,
            recent = false,
            art = art,
            active = active,
            actions = actions,
            haptics = haptics,
            requestArt = requestArt,
            prefetchArt = prefetchArt,
            onEnter = onEnter,
            label = if (app.isNotBlank()) "${name.uppercase()} · ${app.uppercase()}" else name.uppercase(),
            onDoubleBack = onBack,
            swipeUp = if (liked) null else back,
            swipeDown = if (liked) back else null,
        )
        CollectionNote(collection?.note.orEmpty())
    }
}

/** Where the songs came from, shown for a few seconds as the list arrives. */
@Composable
private fun BoxScope.CollectionNote(note: String) {
    if (note.isBlank()) return
    var shown by remember(note) { mutableStateOf(true) }
    LaunchedEffect(note) {
        delay(COLLECTION_NOTE_MS)
        shown = false
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(400)),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
    ) {
        Text(
            text = note,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth(0.66f)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** How long the line saying where the songs came from shows (ms). */
private const val COLLECTION_NOTE_MS = 3_500L

/** What a one-finger swipe up or down does, and the word shown at the edge as it's pulled. */
class SwipeAction(val hint: String, val run: () -> Unit)

/** How long the covers take to dissolve and to sharpen again when switching sets (ms), and the blur at the midpoint. */
private const val SWITCH_OUT_MS = 150
private const val SWITCH_IN_MS = 240
private const val SWITCH_BLUR_DP = 14f

/** [value], once it has held for [ms] (at once when first shown). */
@Composable
private fun <T> settled(value: T, ms: Long): State<T> =
    produceState(initialValue = value, value) {
        delay(ms)
        this.value = value
    }

/** Whether [condition] has held for [ms] (true at once if it already holds when first shown). */
@Composable
private fun heldFor(condition: Boolean, ms: Long): State<Boolean> =
    produceState(initialValue = condition, condition) {
        if (condition && !value) delay(ms)
        value = condition
    }

/**
 * Whether the song has been paused for [ms]: from when the phone says it stopped (or, from an
 * older phone app, when it last reported), turning true right on time. When the song goes away
 * (the player closed, or clearing it for a moment), it holds on a little, until the music counts
 * as stopped (which brings up the same covers), so the covers don't flick back and forth.
 */
@Composable
private fun pausedLongerThan(state: PlayerState?, ms: Long): State<Boolean> {
    val since = if (state != null && state.current != null && !state.isPlaying) {
        state.pausedAt.takeIf { it > 0L } ?: state.capturedAt
    } else {
        0L
    }
    val songGone = state != null && state.current == null
    return produceState(initialValue = since > 0L && System.currentTimeMillis() - since >= ms, since, songGone) {
        if (since <= 0L) {
            if (value && songGone) delay(Tuning.STOPPED_AFTER_MS + 200L)
            value = false
            return@produceState
        }
        val left = since + ms - System.currentTimeMillis()
        if (left > 0L) {
            value = false
            delay(left)
        }
        value = true
    }
}

@Composable
private fun CoverFlowContent(
    state: PlayerState,
    /** The covers: the player's list, or the recently played songs ([recent]). */
    list: List<Track>,
    recent: Boolean,
    art: Map<String, ImageBitmap>,
    active: Boolean,
    actions: ScreenActions,
    haptics: Haptics,
    requestArt: (String) -> Unit,
    prefetchArt: (List<Track>, Int) -> Unit,
    onEnter: (Track) -> Unit,
    /** The same label on every cover (recommended or liked songs), and none marked playing. */
    label: String? = null,
    /** Back ×2 does this (and ×1 opens the cover) instead of both opening it. */
    onDoubleBack: (() -> Unit)? = null,
    swipeUp: SwipeAction? = null,
    swipeDown: SwipeAction? = null,
) {
    val lastIndex = list.lastIndex
    // The recently played songs have no playing one marked (the newest is the one just played).
    val currentIndex = if (recent || label != null) -1 else state.currentIndex
    val currentId = list.getOrNull(currentIndex)?.id

    // Selection survives list refreshes (by id) and trips into the album screen (saveable).
    // Start on the playing song; with nothing playing, on the most recently played one (last);
    // a labelled list (best or newest first) on its first.
    var selected by rememberSaveable {
        mutableIntStateOf(
            when {
                currentIndex in list.indices -> currentIndex
                label != null -> 0
                else -> lastIndex
            },
        )
    }
    var selectedId by rememberSaveable { mutableStateOf(list.getOrNull(selected)?.id.orEmpty()) }
    var seenCurrentId by rememberSaveable { mutableStateOf(currentId) }
    var lastTouch by rememberSaveable { mutableLongStateOf(0L) }
    val index = selected.coerceIn(0, lastIndex)

    LaunchedEffect(list, currentIndex) {
        val byId = list.indexOfFirst { it.id == selectedId }
        val idle = SystemClock.uptimeMillis() - lastTouch > Tuning.FOLLOW_PLAYING_AFTER_IDLE_MS
        val songChanged = currentId != seenCurrentId
        seenCurrentId = currentId
        val target = when {
            songChanged && idle && currentIndex in list.indices -> currentIndex
            // Music stopped: land on the song that was just playing (the newest recent one).
            songChanged && state.current == null -> lastIndex
            byId >= 0 -> byId
            else -> selected.coerceIn(0, lastIndex)
        }
        selected = target
        selectedId = list[target].id
    }

    fun step(delta: Int) {
        val from = selected.coerceIn(0, lastIndex)
        val to = (from + delta).coerceIn(0, lastIndex)
        lastTouch = SystemClock.uptimeMillis()
        if (to != from) {
            selected = to
            selectedId = list[to].id
            // The tick itself comes from TickOnItemChange as each cover passes the center.
        } else {
            haptics.edge()
        }
    }

    fun enter() {
        val track = list.getOrNull(selected.coerceIn(0, lastIndex)) ?: return
        haptics.confirm()
        onEnter(track)
    }

    val stepLatest by rememberUpdatedState<(Int) -> Unit>({ step(it) })
    val enterLatest by rememberUpdatedState<() -> Unit>({ enter() })
    // Single or double press = open the highlighted cover (in a labelled list, double = back to
    // the covers before). Triple (Now Playing) is app-wide.
    val doubleBackLatest by rememberUpdatedState(onDoubleBack)
    SideEffect {
        if (active) {
            actions.onPress = { presses ->
                val back = doubleBackLatest
                when {
                    presses == 2 && back != null -> {
                        back()
                        true
                    }
                    presses <= 2 -> {
                        enterLatest()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    // Covers chase the selection with a spring that keeps its speed between bezel clicks, so a
    // fast spin streams covers past fluidly instead of jumping.
    val position = rememberFlowPosition(index)
    // One haptic tick per cover passing the center, for bezel, taps and swipes alike.
    TickOnItemChange(position, haptics) { SystemClock.uptimeMillis() - lastTouch < 1_500 }

    // The one pane of glass at the centre that the covers move into and out of. It grows in
    // like a droplet when the screen opens. Its sides are liquid: the side a cover leaves by
    // stretches out after it, and the side a cover comes in by is pushed in, and each springs
    // back, with a little bounce, once the cover is out or in.
    val paneAppear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { paneAppear.animateTo(1f, glassSpring()) }
    val paneEdges = remember { PaneEdges() }
    val indexLatest by rememberUpdatedState(index)
    LaunchedEffect(position, paneEdges) {
        var last = 0L
        while (true) {
            if (paneEdges.isStill() && abs(position.value - indexLatest) < 0.0005f) {
                // At rest: sleep until the covers move again.
                paneEdges.rest()
                snapshotFlow { abs(position.value - indexLatest) >= 0.0005f }.first { it }
                last = 0L
            }
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                last = now
                paneEdges.follow(position.value, indexLatest, dt)
            }
        }
    }

    // Load every cover in the list (nearest first) so fast spins never show placeholders.
    LaunchedEffect(list) { prefetchArt(list, index) }
    LaunchedEffect(index) {
        for (i in (index - 4)..(index + 4)) list.getOrNull(i)?.let { requestArt(it.artKey) }
    }

    // The background follows the highlighted cover once it settles (a fast spin doesn't blur
    // and fade every cover flying past).
    val highlighted = list[index]
    val backgroundTrack by produceState(highlighted, highlighted.artKey) {
        delay(150)
        value = highlighted
    }

    // A one-finger swipe up or down: how far it's pulled (px), springing back when let go. The
    // row of covers (and its pane and titles) follows the finger a little.
    val pull = remember { Pull() }
    val swipeUpLatest = rememberUpdatedState(swipeUp)
    val swipeDownLatest = rememberUpdatedState(swipeDown)
    val rowShift = { pull.value * PULL_FOLLOW }

    val scene = rememberGlassScene()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .rotarySteps(active) { stepLatest(it) }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val dx = tap.x - size.width / 2f
                    when {
                        abs(dx) <= size.width * Tuning.COVER_SIZE / 2f -> enterLatest()
                        dx > 0 -> stepLatest(1)
                        else -> stepLatest(-1)
                    }
                }
            }
            .verticalSwipes(pull, swipeUpLatest, swipeDownLatest, haptics)
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                ) { change, amount ->
                    change.consume()
                    dragged -= amount // finger moving left brings the next cover in from the right
                    val stepPx = size.width * Tuning.COVER_SIZE * 0.5f
                    while (dragged >= stepPx) {
                        dragged -= stepPx
                        stepLatest(1)
                    }
                    while (dragged <= -stepPx) {
                        dragged += stepPx
                        stepLatest(-1)
                    }
                }
            },
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val coverPx = widthPx * Tuning.COVER_SIZE
        val coverDp = with(density) { coverPx.toDp() }
        val centerY = heightPx * Tuning.COVER_CENTER_Y

        // Behind the glass: the highlighted album's cover, blurred (as on Now Playing), and the
        // covers, so the glass bends them (the pane at the centre, the domed crystal at the
        // screen's edge, the status pill over the reflections).
        Box(
            Modifier
                .fillMaxSize()
                .glassBackdrop(scene),
        ) {
            BlurredCover(
                track = backgroundTrack,
                art = art[backgroundTrack.artKey],
                modifier = Modifier.zIndex(-200f),
            )
            // Which covers are on screen changes only as the flow passes a whole cover; their
            // places are worked out as they're drawn, so a turning flow redraws but never rebuilds.
            val window by remember(position, lastIndex) {
                derivedStateOf {
                    val pos = position.value
                    max(0, floor(pos).toInt() - 4) to min(lastIndex, ceil(pos).toInt() + 4)
                }
            }
            val (first, last) = window
            for (i in first..last) {
                key(i) {
                    val track = list[i]
                    FlowCover(
                        track = track,
                        art = art[track.artKey],
                        offsetFromCenter = { i - position.value },
                        coverDp = coverDp,
                        coverPx = coverPx,
                        centerYPx = centerY,
                        shiftY = rowShift,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }

        // The pane: its sides follow [paneEdges] (stretched out after a leaving cover, pushed in
        // by an arriving one) and bow the same way (outward as they stretch, inward as they're
        // pushed), it thins a little as it stretches, and it bends the light harder while a
        // cover is on its way.
        GlassSurface(scene) {
            val appear = paneAppear.value
            if (appear < 0.01f) return@GlassSurface emptyList()
            val p = position.value
            val moving = sin(PI.toFloat() * abs(p - p.roundToInt())) // 0 at rest, 1 halfway
            val half = (coverPx / 2f + Tuning.COVER_CASE_DP.dp.toPx()) * appear
            val left = paneEdges.left.dp.toPx() * appear
            val right = paneEdges.right.dp.toPx() * appear
            val halfHeight = half * (1f - PANE_THIN * (left + right) / (2f * half))
            listOf(
                GlassShape(
                    Rect(size.width / 2f - half - left, centerY + rowShift() - halfHeight, size.width / 2f + half + right, centerY + rowShift() + halfHeight),
                    GlassLooks.coverPane(moving),
                    bendLeft = left * PANE_BEND,
                    bendRight = right * PANE_BEND,
                ),
            )
        }

        // On the glass.
        val track = list[index]
        TrackInfo(
            scene = scene,
            track = track,
            isCurrent = index == currentIndex,
            isPlaying = state.isPlaying,
            recentlyPlayed = recent || state.current == null,
            fixedLabel = label,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (centerY + rowShift() + coverPx / 2f + (Tuning.COVER_CASE_DP + 4f).dp.toPx()).roundToInt()) }
                .fillMaxWidth(0.74f),
        )

        // The clock on its glass pill, a little higher than on Now Playing so it clears the
        // centre cover's case.
        val caseTop = with(density) { (centerY - coverPx / 2f).toDp() } - Tuning.COVER_CASE_DP.dp
        GlassClock(scene, top = (caseTop - CLOCK_CLEARANCE).coerceIn(6.dp, (LocalConfiguration.current.screenHeightDp * 0.045f + 4f).dp))

        SwipeHints(pull, swipeUp, swipeDown, heightPx)
    }
}

/** How far a swipe must pull (share of the screen's height) to count, and how much the row follows it. */
private const val SWIPE_FRACTION = 0.16f
private const val PULL_FOLLOW = 0.3f

/** How far a one-finger swipe up (negative) or down (positive) is pulled, in px. */
private class Pull {
    var value by mutableFloatStateOf(0f)
    var settle: Job? = null
}

/**
 * One-finger swipes up and down: [pull] follows the finger (the ways that lead nowhere resist),
 * a tick says when it's far enough, and once let go there the swipe's action runs; either way
 * it springs back.
 */
private fun Modifier.verticalSwipes(
    pull: Pull,
    up: State<SwipeAction?>,
    down: State<SwipeAction?>,
    haptics: Haptics,
): Modifier = pointerInput(Unit) {
    coroutineScope {
        var armed = false
        fun far() = size.height * SWIPE_FRACTION
        fun springBack() {
            pull.settle = launch {
                animate(pull.value, 0f, animationSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 420f)) { v, _ -> pull.value = v }
            }
        }
        detectVerticalDragGestures(
            onDragStart = {
                pull.settle?.cancel()
                armed = false
            },
            onDragEnd = {
                val p = pull.value
                val action = when {
                    p <= -far() -> up.value
                    p >= far() -> down.value
                    else -> null
                }
                springBack()
                action?.run?.invoke()
            },
            onDragCancel = { springBack() },
        ) { change, amount ->
            change.consume()
            val next = pull.value + amount
            val leadsSomewhere = (next < 0f && up.value != null) || (next > 0f && down.value != null)
            pull.value = if (leadsSomewhere) next else pull.value + amount * 0.15f
            val reached = leadsSomewhere && abs(pull.value) >= far()
            if (reached != armed) {
                armed = reached
                if (reached) haptics.tick()
            }
        }
    }
}

/**
 * The word for where a swipe leads, coming in at the edge it's pulled away from: "↑ LIKED"
 * along the bottom as the covers are pulled up, "RECOMMENDED ↓" along the top as they're pulled down.
 * It brightens once the pull is far enough.
 */
@Composable
private fun BoxScope.SwipeHints(pull: Pull, up: SwipeAction?, down: SwipeAction?, heightPx: Float) {
    val far = heightPx * SWIPE_FRACTION
    up?.let { action ->
        SwipeHint(
            text = "↑  ${action.hint}",
            progress = { (-pull.value / far).coerceIn(0f, 1.2f) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            fromBelow = true,
        )
    }
    down?.let { action ->
        SwipeHint(
            text = "${action.hint}  ↓",
            progress = { (pull.value / far).coerceIn(0f, 1.2f) },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
            fromBelow = false,
        )
    }
}

@Composable
private fun SwipeHint(text: String, progress: () -> Float, modifier: Modifier, fromBelow: Boolean) {
    Text(
        text = text,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .graphicsLayer {
                val p = progress()
                alpha = (p * 1.4f).coerceIn(0f, 1f) * (if (p >= 1f) 1f else 0.75f)
                val s = if (p >= 1f) 1.08f else 0.9f + 0.1f * p.coerceAtMost(1f)
                scaleX = s
                scaleY = s
                translationY = (1f - p.coerceAtMost(1f)) * 14.dp.toPx() * (if (fromBelow) 1f else -1f)
            }
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** One cover in the flow, turned and pushed back according to its distance from the center. */
@Composable
private fun FlowCover(
    track: Track,
    art: ImageBitmap?,
    /** Read as the cover is placed and drawn (so moving it needs no rebuilding). */
    offsetFromCenter: () -> Float,
    coverDp: Dp,
    coverPx: Float,
    centerYPx: Float,
    /** How far the row is pulled up or down (px; read as it's placed). */
    shiftY: () -> Float,
    modifier: Modifier,
) {
    val pivotY = 0.5f / (1f + Tuning.REFLECTION)

    Column(
        modifier
            // Nearer covers in front (as Modifier.zIndex, but read as it's placed).
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(0, 0, zIndex = -abs(offsetFromCenter()))
                }
            }
            .offset { IntOffset(0, (centerYPx + shiftY() - coverPx / 2f).roundToInt()) }
            .width(coverDp)
            .graphicsLayer {
                val d = offsetFromCenter()
                val ad = abs(d)
                val t = min(ad, 1f)
                val side = sign(d)
                // Right-hand covers turn their faces toward the center (negative Y rotation), left-hand mirror it.
                translationX = if (ad <= 1f) side * Tuning.SIDE_OFFSET * coverPx * ad
                else side * (Tuning.SIDE_OFFSET * coverPx + (ad - 1f) * Tuning.SIDE_SPACING * coverPx)
                rotationY = -side * Tuning.SIDE_ANGLE * t
                val scale = 1f - (1f - Tuning.SIDE_SCALE) * t
                scaleX = scale
                scaleY = scale
                alpha = if (ad > 3.5f) (1f - (ad - 3.5f) * 2f).coerceIn(0f, 1f) else 1f
                cameraDistance = 6f
                transformOrigin = TransformOrigin(0.5f, pivotY)
            },
    ) {
        Box(
            Modifier
                .size(coverDp)
                .drawWithContent {
                    drawContent()
                    val dim = coverDim(offsetFromCenter())
                    if (dim > 0.01f) drawRect(Color.Black.copy(alpha = dim))
                },
        ) {
            CoverArt(track, art, Modifier.fillMaxSize())
        }
        // Mirror image underneath, fading out to transparent so the album colour shows through.
        Box(
            Modifier
                .width(coverDp)
                .height(coverDp * Tuning.REFLECTION)
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val reflectionAlpha = 0.42f * (1f - coverDim(offsetFromCenter()) * 0.6f)
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = reflectionAlpha), Color.Transparent)),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            CoverArt(
                track,
                art,
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .height(coverDp)
                    .graphicsLayer { scaleY = -1f },
            )
        }
    }
}

/** How much a cover [d] covers away from the centre is dimmed. */
private fun coverDim(d: Float): Float {
    val ad = abs(d)
    return (0.45f * min(ad, 1f) + 0.12f * max(0f, ad - 1f)).coerceAtMost(0.8f)
}

@Composable
private fun TrackInfo(
    scene: GlassScene,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    recentlyPlayed: Boolean,
    modifier: Modifier,
    fixedLabel: String? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Status label on a pill of liquid glass. It grows in like a droplet when you reach the
        // playing song (or the recent songs) and melts away when you scroll past.
        val label = when {
            fixedLabel != null -> fixedLabel
            // Nothing is playing: these are recent songs; picking one plays it in this app.
            recentlyPlayed -> if (track.sourceName.isNotBlank()) "RECENT · ${track.sourceName.uppercase()}" else "RECENTLY PLAYED"
            isCurrent -> if (isPlaying) "▶  NOW PLAYING" else "❚❚  PAUSED"
            else -> null
        }
        if (label != null) {
            Text(
                text = label,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = if (recentlyPlayed) 0.8f else 0.95f),
                maxLines = 1,
                modifier = Modifier
                    // Never melts into the cover's case just above it.
                    .glass(scene, GlassLooks.Pill.copy(merge = 0.dp))
                    .padding(horizontal = 9.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(3.dp))
        }
        MarqueeText(
            track.title.ifBlank { "Unknown song" },
            15.sp,
            Color.White,
            Modifier.fillMaxWidth(),
            FontWeight.SemiBold,
        )
        if (track.artist.isNotBlank()) {
            MarqueeText(track.artist, 12.sp, Color.White.copy(alpha = 0.8f), Modifier.fillMaxWidth())
        }
        if (track.album.isNotBlank()) {
            MarqueeText(track.album, 11.sp, Color.White.copy(alpha = 0.6f), Modifier.fillMaxWidth())
        }
    }
}

/** How far above the centre case's top the clock's pill starts (its height plus a gap). */
private val CLOCK_CLEARANCE = 23.dp

/**
 * The pane's liquid sides: how far (dp) a side stretches out after a leaving cover and is pushed
 * in by an arriving one (at most, with the covers halfway; its middle bows a little further, see
 * [PANE_BEND]), how much thinner it gets as it stretches (and fuller as it's pushed in), and the
 * spring each side follows (a little bouncy).
 */
private const val PANE_STRETCH_DP = 10f
private const val PANE_DENT_DP = 7f
private const val PANE_THIN = 0.35f

/** How far a side's middle bows (out as it stretches, in as it's pushed), per dp it moves. */
private const val PANE_BEND = 0.7f
private const val PANE_SPRING_STIFFNESS = 380f
private const val PANE_SPRING_DAMPING = 0.4f

/**
 * How far (dp) each side of the pane is out (+) or in (−) from its usual place, following where
 * the covers are on a bouncy spring.
 */
private class PaneEdges {
    var left by mutableFloatStateOf(0f)
    var right by mutableFloatStateOf(0f)
    private var leftSpeed = 0f
    private var rightSpeed = 0f

    fun isStill() = abs(left) < 0.05f && abs(right) < 0.05f && abs(leftSpeed) < 0.5f && abs(rightSpeed) < 0.5f

    fun rest() {
        left = 0f
        right = 0f
        leftSpeed = 0f
        rightSpeed = 0f
    }

    /**
     * One frame ([dt] s) of following the flow at [position], heading for [target]: going on to
     * later covers, the cover in the pane leaves by the left (that side stretches after it) while
     * the next comes in from the right (that side is pushed in); going back, the other way round.
     * Both are greatest with the covers halfway, and gone once a cover rests in the pane.
     */
    fun follow(position: Float, target: Int, dt: Float) {
        val halfway = sin(PI.toFloat() * (position - floor(position)))
        val (leftGoal, rightGoal) = when {
            target > position + 0.0005f -> PANE_STRETCH_DP * halfway to -PANE_DENT_DP * halfway
            target < position - 0.0005f -> -PANE_DENT_DP * halfway to PANE_STRETCH_DP * halfway
            else -> 0f to 0f
        }
        val k = PANE_SPRING_STIFFNESS
        val c = 2f * PANE_SPRING_DAMPING * sqrt(k)
        var l = left
        var r = right
        repeat(4) {
            val h = dt / 4f
            leftSpeed += (-k * (l - leftGoal) - c * leftSpeed) * h
            l += leftSpeed * h
            rightSpeed += (-k * (r - rightGoal) - c * rightSpeed) * h
            r += rightSpeed * h
        }
        left = l
        right = r
    }
}
