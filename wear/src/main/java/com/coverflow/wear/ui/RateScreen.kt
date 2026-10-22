package com.coverflow.wear.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.coverflow.shared.PlayerState
import com.coverflow.shared.SongRating
import com.coverflow.shared.Track
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

private val StarGold = Color(0xFFFFC53D)

/**
 * Rate the playing song out of 5 stars, over its cover blurred (as on Now Playing). The bezel
 * picks the stars; nothing is saved until you confirm. 3 stars or more also likes the song in the player app; 1-2 stars puts it back to not
 * liked (a thumbs-down where the app has one); no stars leaves likes alone.
 *
 * Back: 1 = back without saving, 2 = save (then back), 3 = Now Playing.
 */
@Composable
fun RateScreen(
    state: PlayerState?,
    ratings: Map<String, SongRating>,
    art: Map<String, ImageBitmap>,
    active: Boolean,
    actions: ScreenActions,
    haptics: Haptics,
    requestArt: (String) -> Unit,
    requestRating: (Track) -> Unit,
    onSave: (Track, SongRating) -> Unit,
    onDone: () -> Unit,
) {
    val current = state?.current
    LaunchedEffect(current?.songKey) {
        current?.let {
            requestArt(it.artKey)
            requestRating(it)
        }
    }

    val saved = current?.let { ratings[it.songKey] ?: SongRating(it.songKey) }
    // -1 = untouched (show the saved stars). Reset when the song changes.
    var pending by rememberSaveable { mutableIntStateOf(-1) }
    var pendingFor by rememberSaveable { mutableStateOf(current?.songKey) }
    LaunchedEffect(current?.songKey) {
        if (pendingFor != current?.songKey) {
            pendingFor = current?.songKey
            pending = -1
        }
    }
    val shown = if (pending >= 0) pending else saved?.stars ?: 0
    var justSaved by remember { mutableStateOf(false) }

    val latestTrack by rememberUpdatedState(current)
    val latestSaved by rememberUpdatedState(saved)
    val latestShown by rememberUpdatedState(shown)
    val doneLatest by rememberUpdatedState(onDone)

    fun step(steps: Int) {
        if (justSaved) return
        val from = latestShown
        val to = (from + steps).coerceIn(0, 5)
        if (to == from) {
            haptics.edge()
        } else {
            pending = to
            haptics.tick()
        }
    }

    fun save() {
        if (justSaved) return
        val track = latestTrack ?: return
        val before = latestSaved ?: return
        val stars = latestShown
        // 3+ stars likes the song, 1-2 stars un-likes it, 0 leaves it.
        val liked = when {
            stars >= 3 -> true
            stars in 1..2 -> false
            else -> before.liked
        }
        onSave(track, before.copy(stars = stars, liked = liked))
        pending = -1
        justSaved = true
        haptics.strong()
    }

    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(700)
            doneLatest()
        }
    }

    val stepLatest by rememberUpdatedState<(Int) -> Unit>({ step(it) })
    val saveLatest by rememberUpdatedState<() -> Unit>({ save() })
    SideEffect {
        if (active) actions.onPress = { presses ->
            when (presses) {
                2 -> {
                    saveLatest()
                    true
                }
                4 -> true // already on the rating page
                else -> false // 1 = back, 3 = Now Playing
            }
        }
    }

    val scene = rememberGlassScene()

    // The rating as a place along the row of stars, which a droplet of gold glass follows: it
    // springs from star to star, thinning and bending the light harder on the way, and grows
    // back to full size on the new star. Each star turns gold as the droplet reaches it.
    val dropPos by animateFloatAsState(
        targetValue = shown.toFloat(),
        animationSpec = glassSpring(),
        label = "ratingDroplet",
    )
    val dropAppear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { dropAppear.animateTo(1f, glassSpring()) }
    val starSpots = remember { arrayOfNulls<LayoutCoordinates>(5) }
    var starsMoved by remember { mutableIntStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            .rotarySteps(active) { stepLatest(it) },
        contentAlignment = Alignment.Center,
    ) {
        // Behind the glass: the cover blurred (as on Now Playing) and the stars themselves, so
        // the glass circles and the droplet bend them.
        Box(
            Modifier
                .fillMaxSize()
                .glassBackdrop(scene),
        ) {
            BlurredCover(current, current?.let { art[it.artKey] })
            Box(
                Modifier.fillMaxSize().drawBehind {
                    starsMoved // redraw when the row moves
                    val p = dropPos
                    for (n in 1..5) {
                        val spot = starSpots[n - 1]?.let { scene.boundsOf(it) } ?: continue
                        val fill = smoothStep(n - 0.6f, n - 0.15f, p)
                        val outer = 10.dp.toPx() * (0.86f + 0.14f * fill)
                        val center = Offset(spot.center.x, spot.center.y + 0.8.dp.toPx())
                        if (fill < 1f) drawStar(center, outer, Color.White.copy(alpha = 0.55f * (1f - fill)), filled = false)
                        if (fill > 0f) drawStar(center, outer, StarGold.copy(alpha = fill), filled = true)
                    }
                },
            )
        }
        GlassSurface(scene) {
            starsMoved
            val centers = starSpots.map { spot -> spot?.let { scene.boundsOf(it) }?.center }
            if (current == null || centers.any { it == null }) {
                emptyList()
            } else {
                listOfNotNull(ratingDroplet(dropPos, centers.filterNotNull(), dropAppear.value))
            }
        }
        if (current == null || saved == null) {
            Text("Nothing playing", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))) {
                    CoverArt(current, art[current.artKey], Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(4.dp))
                MarqueeText(current.title, 12.sp, Color.White, Modifier.fillMaxWidth(0.7f), FontWeight.SemiBold)
                if (current.artist.isNotBlank()) {
                    MarqueeText(current.artist, 10.sp, Color.White.copy(alpha = 0.7f), Modifier.fillMaxWidth(0.66f))
                }
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (n in 1..5) {
                        StarSpot(
                            scene = scene,
                            onPlaced = {
                                starSpots[n - 1] = it
                                starsMoved++
                            },
                        ) {
                            if (!justSaved) {
                                pending = n
                                haptics.tick()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                val changed = pending >= 0 && pending != saved.stars
                Text(
                    text = when {
                        justSaved -> "Saved ✓"
                        changed -> "Back ×2 to save"
                        shown == 0 -> "Not rated"
                        else -> "Rated $shown of 5"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (justSaved) StarGold else Color.White,
                )
                Text(
                    text = likeNote(shown, saved, state?.player.orEmpty()),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(0.78f).padding(top = 2.dp),
                )
                if (changed && !justSaved) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Save",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                        modifier = Modifier
                            .glass(scene, GlassLooks.tinted(StarGold, 0.85f))
                            .clip(RoundedCornerShape(50))
                            .clickable { save() }
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
        }
        GlassClock(scene)
    }
}

private fun likeNote(stars: Int, saved: SongRating, player: String): String {
    val app = player.ifBlank { "your player" }
    return when {
        stars == 0 -> "No rating leaves likes as they are"
        !saved.likeInPlayer -> "$app can't be liked from here; saved in Cover Display"
        stars >= 3 -> "3★ or more also likes it in $app"
        else -> "Under 3★ removes the like in $app"
    }
}

/**
 * One star's place: a circle of clear glass that stays apart from its neighbours. The star
 * itself is drawn behind the glass (so the glass bends it), at the spot this reports.
 */
@Composable
private fun StarSpot(scene: GlassScene, onPlaced: (LayoutCoordinates) -> Unit, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .glass(scene, GlassLooks.StarCircle)
            .onGloballyPositioned(onPlaced)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    )
}

private const val DROP_RADIUS_DP = 16.5f

/**
 * The gold droplet at rating position [p] (0 to 5) over the star [centers]: on a star it is a
 * full-size lens; between stars it thins to half (stretched along its way) and grows back as it
 * arrives. Below 1 it shrinks away into the first star.
 */
private fun DrawScope.ratingDroplet(p: Float, centers: List<Offset>, appear: Float): GlassShape? {
    if (p <= 0.02f || appear <= 0.01f || centers.size < 5) return null
    val from: Int
    val to: Int
    val frac: Float
    val grow: Float
    if (p < 1f) {
        from = 0
        to = 0
        frac = 0f
        grow = smoothStep(0f, 1f, p)
    } else {
        from = min(floor(p).toInt(), 5) - 1
        to = min(from + 1, 4)
        frac = if (from < 4) p - floor(p) else min(p - 5f, 0.5f) // past 5: the spring's overshoot
        grow = 1f
    }
    val motion = sin(PI.toFloat() * frac)
    val a = centers[from]
    val b = centers[to]
    val cx = a.x + (b.x - a.x) * frac
    val cy = a.y + (b.y - a.y) * frac
    val r = DROP_RADIUS_DP.dp.toPx() * grow * appear * (1f - 0.5f * motion)
    val hw = r * (1f + 0.30f * motion)
    val hh = r * (1f - 0.12f * motion)
    return GlassShape(Rect(cx - hw, cy - hh, cx + hw, cy + hh), GlassLooks.droplet(StarGold, motion))
}

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
