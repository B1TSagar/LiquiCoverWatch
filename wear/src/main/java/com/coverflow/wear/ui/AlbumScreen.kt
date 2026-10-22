package com.coverflow.wear.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.coverflow.shared.AlbumResponse
import com.coverflow.shared.PlayerState
import com.coverflow.shared.Track
import com.coverflow.wear.CoverColors
import com.coverflow.wear.Tuning
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The highlighted cover flips over (like the iPod) to show its album's tracks.
 * Bezel moves the highlight, a double Back press plays the highlighted song and opens Now Playing.
 */
@Composable
fun AlbumScreen(
    cover: Track,
    album: AlbumResponse?,
    state: PlayerState?,
    art: Map<String, ImageBitmap>,
    artColors: Map<String, Color>,
    active: Boolean,
    actions: ScreenActions,
    haptics: Haptics,
    requestArt: (String) -> Unit,
    requestAlbum: (Track) -> Unit,
    onPlay: (track: Track, alreadyPlaying: Boolean) -> Unit,
) {
    LaunchedEffect(cover) {
        requestArt(cover.artKey)
        requestAlbum(cover)
    }
    // A list made only of the songs the phone knew (its catalog lookup failed for a moment, or
    // was slow) is asked for again a few times while the album is open, until the full one comes.
    LaunchedEffect(cover, album?.fromCatalog) {
        if (album == null || album.fromCatalog) return@LaunchedEffect
        repeat(ALBUM_RETRIES) {
            delay(ALBUM_RETRY_MS)
            requestAlbum(cover)
        }
    }

    // Flip animation runs once; coming back from Now Playing shows the back face directly.
    var flipped by rememberSaveable { mutableStateOf(false) }
    val flip = remember { Animatable(if (flipped) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!flipped) {
            delay(40)
            flip.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
            flipped = true
        }
    }

    val tracks = album?.tracks?.takeIf { it.isNotEmpty() } ?: listOf(cover)
    val lastIndex = tracks.lastIndex
    val coverIndex = tracks.indexOfFirst { it.sameSongAs(cover) }.coerceAtLeast(0)
    // The highlighted song, by song (not row): a fuller list arriving meanwhile can move its row.
    var picked by rememberSaveable { mutableStateOf<String?>(null) } // null = follow the entered cover's song
    val index = picked?.let { key -> tracks.indexOfFirst { it.songKey == key }.takeIf { it >= 0 } } ?: coverIndex

    fun step(delta: Int) {
        val to = (index + delta).coerceIn(0, lastIndex)
        if (to != index) {
            picked = tracks[to].songKey // tick comes from TickOnItemChange as the row passes the bar
        } else {
            haptics.edge()
        }
    }

    fun play() {
        val track = tracks.getOrNull(index) ?: return
        val alreadyPlaying = state != null && state.isPlaying && state.current?.sameSongAs(track) == true
        haptics.confirm()
        onPlay(track, alreadyPlaying)
    }

    val stepLatest by rememberUpdatedState<(Int) -> Unit>({ step(it) })
    val playLatest by rememberUpdatedState<() -> Unit>({ play() })
    // Double press = play the highlighted song. Single (back) and triple (Now Playing) are app-wide.
    SideEffect {
        if (active) actions.onPress = { presses -> if (presses == 2) { playLatest(); true } else false }
    }

    val position = rememberFlowPosition(index)
    TickOnItemChange(position, haptics) { picked != null }

    val ready = flip.value >= 1f
    val tint = CoverColors.backgroundTint(coverColor(cover, artColors), Tuning.BACKGROUND_BRIGHTNESS * 0.8f)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .rotarySteps(active && ready) { stepLatest(it) },
    ) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        if (!ready) {
            // The cover grows from its Cover Flow spot to full screen while turning over.
            val p = flip.value
            val coverPx = w * Tuning.COVER_SIZE
            val sizePx = coverPx + (w - coverPx) * p
            val centerY = h * Tuning.COVER_CENTER_Y + (h / 2f - h * Tuning.COVER_CENTER_Y) * p
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, (centerY - sizePx / 2f).roundToInt()) }
                    .size(with(density) { sizePx.toDp() })
                    .graphicsLayer {
                        rotationY = p * 180f
                        cameraDistance = 10f
                        shape = RoundedCornerShape(percent = (p * 50f).roundToInt())
                        clip = true
                    },
            ) {
                if (p < 0.5f) {
                    CoverArt(cover, art[cover.artKey], Modifier.fillMaxSize())
                } else {
                    // The back of the cover: the cover blurred (mirrored back, as the card is
                    // turned over).
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = -1f },
                    ) {
                        BlurredCover(cover, art[cover.artKey])
                    }
                }
            }
        } else {
            TrackWheel(
                tracks = tracks,
                index = index,
                position = { position.value },
                album = album,
                cover = cover,
                coverArt = art[cover.artKey],
                state = state,
                tint = tint,
                widthPx = w,
                heightPx = h,
                onTapRow = { d -> if (d == 0) playLatest() else stepLatest(d) },
                onDragStep = { stepLatest(it) },
            )
        }
    }
}

/**
 * How much of a row is left as it rises toward the header or sinks toward the footer:
 * 1 = all of it, 0 = gone. Rows (and their glass) shrink away like droplets instead of
 * sliding under the header. At rest every row is either whole or gone, so no half-shrunk
 * capsule sits under the header.
 */
private fun rowPresence(y: Float, h: Float): Float =
    min((y - h * 0.34f) / (h * 0.12f), (h * 0.86f - y) / (h * 0.10f)).coerceIn(0f, 1f)

/**
 * One row's shape, [d] rows from the selection: how selected it is (1 on the selection,
 * easing to 0 a row away; it brightens the text), its width and glass height (px, before
 * [scale]), and the gentle shrink with distance.
 */
private class RowShape(val selection: Float, val width: Float, val glassHeight: Float, val scale: Float)

private fun rowShape(d: Float, widthPx: Float, rowPx: Float): RowShape {
    val ad = abs(d)
    val t = (1f - ad).coerceIn(0f, 1f)
    return RowShape(
        selection = t * t * (3f - 2f * t),
        width = widthPx * 0.79f,
        glassHeight = rowPx * 0.80f,
        scale = 1f - 0.05f * min(ad, 2f),
    )
}

/** Gap between a row's number bubble and its name capsule. */
private val NumberGap = 4.dp

/**
 * The rolling track list, on liquid glass. Each track is two pieces of clear glass: a bubble
 * for its number and a capsule for its name, with the text behind them so the glass bends it.
 * The selection is a pair of droplets filled with the album colour (a gradient), a little
 * bigger than the pieces they sit on, like the Rate page's star droplet: as the list rolls,
 * they thin and bend the light harder as they leave one track, then grow back onto the next.
 * At rest every piece stands apart; while the list moves, the pieces melt into each other and
 * into their neighbours, and tracks shrink away like droplets toward the header and footer.
 */
@Composable
private fun TrackWheel(
    tracks: List<Track>,
    index: Int,
    /** Read as rows are placed and drawn (so rolling the list needs no rebuilding). */
    position: () -> Float,
    album: AlbumResponse?,
    cover: Track,
    coverArt: ImageBitmap?,
    state: PlayerState?,
    tint: Color,
    widthPx: Float,
    heightPx: Float,
    onTapRow: (Int) -> Unit,
    onDragStep: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val rowPx = heightPx * 0.135f
    val centerY = heightPx * 0.60f
    val rowDp = with(density) { rowPx.toDp() }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220)) }
    val latestTap by rememberUpdatedState(onTapRow)
    val latestDrag by rememberUpdatedState(onDragStep)
    val scene = rememberGlassScene()
    val numberTint = lerp(tint, Color.White, 0.55f)
    val nameTintLeft = lerp(tint, Color.White, 0.45f)
    val nameTintRight = lerp(tint, Color.White, 0.16f)
    val gapDp = NumberGap
    // Which rows exist changes only as the list passes a whole row.
    val window by remember(tracks) {
        derivedStateOf {
            val p = position()
            max(0, floor(p).toInt() - 3) to min(tracks.lastIndex, ceil(p).toInt() + 4)
        }
    }
    val (first, last) = window
    val rowWidthDp = with(density) { rowShape(0f, widthPx, rowPx).width.toDp() }
    val glassDp = with(density) { rowShape(0f, widthPx, rowPx).glassHeight.toDp() }

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(percent = 50))
            .graphicsLayer { alpha = appear.value }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val d = ((tap.y - size.height * 0.60f) / (size.height * 0.135f)).roundToInt()
                    latestTap(d)
                }
            }
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(onDragStart = { dragged = 0f }) { change, amount ->
                    change.consume()
                    dragged -= amount // finger up = next song
                    val stepPx = size.height * 0.135f
                    while (dragged >= stepPx) {
                        dragged -= stepPx
                        latestDrag(1)
                    }
                    while (dragged <= -stepPx) {
                        dragged += stepPx
                        latestDrag(-1)
                    }
                }
            },
    ) {
        // Behind the glass: the cover blurred (as on Now Playing) and the track titles.
        Box(
            Modifier
                .fillMaxSize()
                .glassBackdrop(scene),
        ) {
            BlurredCover(cover, coverArt)
            for (i in first..last) {
                key(i) {
                    val track = tracks[i]
                    val selected by remember(i) { derivedStateOf { abs(i - position()) < 0.5f } }
                    val playing = state?.current?.sameSongAs(track) == true
                    /** How selected the row is right now (1 on the selection, 0 a row away). */
                    fun selection() = rowShape(i - position(), widthPx, rowPx).selection
                    // Laid out to match the glass pieces exactly (see the GlassSurface below).
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset { IntOffset(0, (centerY + (i - position()) * rowPx - rowPx / 2f).roundToInt()) }
                            .width(rowWidthDp)
                            .height(rowDp)
                            .graphicsLayer {
                                val d = i - position()
                                val ad = abs(d)
                                val presence = rowPresence(centerY + d * rowPx, heightPx)
                                val shape = rowShape(d, widthPx, rowPx)
                                scaleX = shape.scale
                                scaleY = shape.scale
                                // Gone toward the header and footer (it shrinks away with its glass).
                                alpha = ((1f - 0.32f * ad) * presence).coerceIn(0f, 1f)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(glassDp), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (track.trackNumber > 0) "${track.trackNumber}" else "${i + 1}",
                                fontSize = 11.sp,
                                maxLines = 1,
                                color = Color.White,
                                modifier = Modifier.graphicsLayer { alpha = 0.5f + 0.35f * selection() },
                            )
                        }
                        Spacer(Modifier.width(gapDp))
                        Row(
                            Modifier.weight(1f).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selected) {
                                MarqueeText(track.title, 13.sp, Color.White, Modifier.weight(1f), FontWeight.Medium)
                            } else {
                                Text(
                                    text = track.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (playing) {
                                Spacer(Modifier.width(4.dp))
                                GlyphIcon(
                                    Glyph.Play,
                                    Color.White,
                                    Modifier.size(8.dp).graphicsLayer { alpha = 0.7f + 0.3f * selection() },
                                )
                            }
                        }
                    }
                }
            }
        }

        // The liquid glass: a number bubble and a name capsule per track, sized and placed like
        // the rows above, shrinking away with them, then the two selection droplets. At rest
        // every piece stands apart; while the list moves they melt into one another, and as it
        // settles they pinch apart again.
        GlassSurface(
            scene,
            merge = {
                val travel = ((abs(position() - index)) / 0.15f).coerceIn(0f, 1f)
                Tuning.TRACK_MERGE_DP * travel * travel * (3f - 2f * travel)
            },
        ) {
            val position = position()
            val shapes = ArrayList<GlassShape>(11)
            val mid = size.width / 2f
            val gap = gapDp.toPx()
            val first = max(0, floor(position).toInt() - 3)
            val last = min(tracks.lastIndex, ceil(position).toInt() + 4)
            for (i in first..last) {
                val d = i - position
                val y = centerY + d * rowPx
                val presence = rowPresence(y, size.height)
                if (presence <= 0f) continue
                val shape = rowShape(d, widthPx, rowPx)
                val k = shape.scale * presence
                val left = mid - shape.width / 2f * k
                val right = mid + shape.width / 2f * k
                val r = shape.glassHeight / 2f * k
                if (r < 1f) continue
                shapes += GlassShape(Rect(left, y - r, left + 2f * r, y + r), GlassLooks.TrackPiece)
                val nameLeft = left + 2f * r + gap * k
                if (right - nameLeft > 2f * r) {
                    shapes += GlassShape(Rect(nameLeft, y - r, right, y + r), GlassLooks.TrackPiece)
                }
            }
            // The selection droplets ride the middle line, where the selected track rests: as
            // a track rolls away and the next rolls in, they thin out between the two (and
            // bend harder), then grow back to full size on the new track.
            val frac = position - floor(position)
            val motion = sin(PI.toFloat() * frac)
            val rest = rowShape(0f, widthPx, rowPx)
            val margin = 2.dp.toPx()
            val left0 = mid - rest.width / 2f
            val r0 = rest.glassHeight / 2f
            val rn = (r0 + margin) * (1f - 0.55f * motion)
            val nx = left0 + r0
            shapes += GlassShape(Rect(nx - rn, centerY - rn, nx + rn, centerY + rn), GlassLooks.trackDroplet(numberTint, numberTint, motion))
            val nameLeft0 = left0 + 2f * r0 + gap
            val nameRight0 = mid + rest.width / 2f
            val cx = (nameLeft0 + nameRight0) / 2f
            val hw = ((nameRight0 - nameLeft0) / 2f + margin) * (1f - 0.72f * motion)
            val hh = (r0 + margin) * (1f - 0.55f * motion)
            shapes += GlassShape(Rect(cx - hw, centerY - hh, cx + hw, centerY + hh), GlassLooks.trackDroplet(nameTintLeft, nameTintRight, motion))
            shapes
        }

        // On the glass: album and artist at the top, position and length at the bottom.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            MarqueeText(
                (album?.album ?: cover.album).ifBlank { cover.title },
                13.sp,
                Color.White,
                Modifier.fillMaxWidth(0.62f),
                FontWeight.Bold,
            )
            val artist = (album?.artist ?: cover.artist)
            if (artist.isNotBlank()) {
                MarqueeText(artist, 11.sp, Color.White.copy(alpha = 0.65f), Modifier.fillMaxWidth(0.6f))
            }
        }

        val footer = when {
            album == null -> "Finding album tracks…"
            else -> {
                val t = tracks[index]
                val len = if (t.durationMs > 0) "  ·  ${formatTime(t.durationMs)}" else ""
                "${index + 1} of ${tracks.size}$len"
            }
        }
        Text(
            text = footer,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) { (heightPx * 0.05f).toDp() })
                .glass(scene, GlassLooks.Smoked)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** How many times, and how often (ms), a partial track list is asked for again while the album is open. */
private const val ALBUM_RETRIES = 3
private const val ALBUM_RETRY_MS = 16_000L
