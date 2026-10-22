package com.coverflow.wear.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import com.coverflow.shared.AlbumRequest
import com.coverflow.shared.CollectionKind
import com.coverflow.shared.Track
import com.coverflow.wear.AmbientState
import com.coverflow.wear.PhoneLink
import com.coverflow.wear.Tuning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** How long a page change takes (ms). */
private const val PAGE_MS = 260

/** The app's pages. The stack's last entry is on screen. */
sealed interface Screen {
    val key: String

    data object Covers : Screen {
        override val key = "covers"
    }

    data class Album(val cover: Track) : Screen {
        override val key = "album:${cover.id}"
    }

    /** Recommended or liked songs from the app that played last ([CollectionKind]). */
    data class Collection(val kind: String) : Screen {
        override val key = "collection:$kind"
    }

    data object NowPlaying : Screen {
        override val key = "now"
    }

    data object Lyrics : Screen {
        override val key = "lyrics"
    }

    data object Rate : Screen {
        override val key = "rate"
    }
}

/**
 * Lets the visible screen claim Back presses. [onPress] gets the press count (1-4) and returns
 * true if it handled it; otherwise the app default applies:
 * 1 = back, 3 = Now Playing, 4 = lyrics.
 */
class ScreenActions {
    var onPress: (Int) -> Boolean = { false }

    /** A two-finger tap on Cover Flow (set by Cover Flow; elsewhere the tap opens the lyrics). */
    var onTwoFingerTap: () -> Unit = {}
}

/**
 * @param lyricsRequests bumps each time the "Lyrics" launcher entry is opened (e.g. from the
 *        watch's Home double-press shortcut).
 * @param twoFingerTaps bumps on each two-finger tap: on Cover Flow it switches between the
 *        player's covers and the recently played songs; on any other page it opens the lyrics.
 * @param ambient always-on display state; while it's on, the always-on version of Now Playing
 *        (or of the lyrics, if they were open) replaces the normal screens.
 */
@Composable
fun CoverFlowApp(
    onExit: () -> Unit,
    lyricsRequests: StateFlow<Int>,
    nowPlayingRequests: StateFlow<Int>,
    twoFingerTaps: StateFlow<Int>,
    ambient: AmbientState,
    link: PhoneLink = viewModel(),
) {
    val state by link.state.collectAsStateWithLifecycle()
    val connection by link.connection.collectAsStateWithLifecycle()
    val lyricsRequest by lyricsRequests.collectAsStateWithLifecycle()
    val nowPlayingRequest by nowPlayingRequests.collectAsStateWithLifecycle()
    val twoFingerTap by twoFingerTaps.collectAsStateWithLifecycle()
    val starting by link.starting.collectAsStateWithLifecycle()
    val startingTrack by link.startingTrack.collectAsStateWithLifecycle()
    val notice by link.notice.collectAsStateWithLifecycle()
    val isSystemAmbient by ambient.isSystemAmbient.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val actions = remember { ScreenActions() }
    val stack = remember { mutableStateListOf<Screen>(Screen.Covers) }
    val saveable = rememberSaveableStateHolder()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { link.sync() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { link.shown() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { link.hidden() }

    // Keep the screen on (dimming when idle) while music plays, and for a while after it pauses.
    val isPlaying = state?.isPlaying == true
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            ambient.setKeepScreenOn(Tuning.KEEP_SCREEN_ON)
        } else {
            delay(Tuning.KEEP_ON_AFTER_PAUSE_MS)
            ambient.setKeepScreenOn(false)
        }
    }

    fun push(screen: Screen) {
        // A fast double tap during a transition shouldn't stack the same page twice.
        if (stack.last().key != screen.key) stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) {
            val gone = stack.removeAt(stack.lastIndex)
            saveable.removeState(gone.key)
            haptics.back()
        } else {
            onExit()
        }
    }

    /** Jump to a page: back down to it if it's already open below, otherwise open it on top. */
    fun goTo(screen: Screen, feedback: Boolean = true) {
        if (stack.last().key == screen.key) return
        if (feedback) haptics.strong()
        val existing = stack.indexOfFirst { it.key == screen.key }
        if (existing >= 0) {
            while (stack.size > existing + 1) {
                val gone = stack.removeAt(stack.lastIndex)
                saveable.removeState(gone.key)
            }
        } else {
            stack.add(screen)
        }
    }

    // Music stopped on the phone while a now-playing page was open: go back to the covers, which
    // now show the recently played songs. The short wait rides out players that briefly clear
    // the song between tracks.
    val nothingPlaying = state != null && state?.current == null
    LaunchedEffect(nothingPlaying, starting) {
        if (nothingPlaying && starting == null) {
            delay(1_500)
            val top = stack.last()
            if (top == Screen.NowPlaying || top == Screen.Lyrics || top == Screen.Rate) {
                goTo(Screen.Covers, feedback = false)
            }
        }
    }

    LaunchedEffect(lyricsRequest) {
        if (lyricsRequest > 0) goTo(Screen.Lyrics)
    }

    // Opened from the LiquiWatch face's song: straight to Now Playing.
    LaunchedEffect(nowPlayingRequest) {
        if (nowPlayingRequest > 0) goTo(Screen.NowPlaying, feedback = false)
    }

    LaunchedEffect(twoFingerTap) {
        if (twoFingerTap > 0) {
            if (stack.last() == Screen.Covers) actions.onTwoFingerTap() else goTo(Screen.Lyrics)
        }
    }

    // Galaxy Watch Back key.
    //   Cover Flow:    1 or 2 = open the cover
    //   Recommended / Liked:  1 = open the cover, 2 = back to the covers before
    //   Album:         1 = back, 2 = play the song
    //   Now Playing:   1 = play/pause, 2 = back, 3 = scrub
    //   Lyrics:        1 = back, 2 = rate the song
    //   Rate:          1 = back without saving, 2 = save
    //   Everywhere:    3 = Now Playing (except on it), 4 = lyrics (as does a two-finger tap,
    //                  except on Cover Flow, where it switches to the recently played covers and back)
    MultiPressBackHandler { presses ->
        // Three presses always lead to Now Playing, whatever page is up (or still arriving);
        // only Now Playing itself uses them (for scrub mode).
        if (presses == 3 && stack.last() != Screen.NowPlaying) {
            goTo(Screen.NowPlaying)
            return@MultiPressBackHandler
        }
        if (!actions.onPress(presses)) {
            when (presses) {
                1 -> pop()
                3 -> goTo(Screen.NowPlaying)
                4 -> goTo(Screen.Lyrics)
            }
        }
    }

    MaterialTheme {
        // Always-on keeps the same screens: dimmed by the activity to a fifth of the brightness
        // (our own always-on), or, in the watch's low-power always-on (which sets the brightness
        // itself), shown at a fifth of their brightness.
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val top = stack.last()
            AnimatedContent(
                targetState = top,
                transitionSpec = {
                    // Popped screens are already gone from the stack.
                    val deeper = initialState in stack && stack.indexOf(targetState) > stack.indexOf(initialState)
                    // Liked songs come up from below (swiped up), recommended ones down from above
                    // (swiped down), and go back the way they came.
                    val list = (targetState as? Screen.Collection)?.takeIf { initialState == Screen.Covers }
                        ?: (initialState as? Screen.Collection)?.takeIf { targetState == Screen.Covers }
                    if (list != null) {
                        val below = list.kind == CollectionKind.LIKED
                        val arriving = targetState is Screen.Collection
                        val enterFrom = if (arriving == below) 1 else -1 // +1: from below
                        (slideInVertically(tween(PAGE_MS + 60)) { h -> enterFrom * h / 3 } + fadeIn(tween(PAGE_MS))) togetherWith
                            (slideOutVertically(tween(PAGE_MS + 60)) { h -> -enterFrom * h / 3 } + fadeOut(tween(PAGE_MS - 60)))
                    } else if (deeper) {
                        fadeIn(tween(PAGE_MS)) togetherWith fadeOut(tween(PAGE_MS - 60))
                    } else {
                        (fadeIn(tween(PAGE_MS)) + scaleIn(tween(PAGE_MS), initialScale = 0.94f)) togetherWith
                            (fadeOut(tween(PAGE_MS - 60)) + scaleOut(tween(PAGE_MS - 40), targetScale = 1.04f))
                    }
                },
                label = "pages",
                // One page per key, so saved state can't be registered twice mid-transition.
                contentKey = { it.key },
            ) { screen ->
                val active = screen == top
                // Pages flow into each other: the leaving page dissolves into a blur while the
                // arriving one sharpens out of it, instead of one simply covering the other.
                val dissolve by transition.animateFloat(
                    transitionSpec = { tween(PAGE_MS) },
                    label = "dissolve",
                ) { if (it == EnterExitState.Visible) 0f else 1f }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            renderEffect = if (Build.VERSION.SDK_INT >= 31 && dissolve > 0.01f) {
                                val r = dissolve * 14.dp.toPx()
                                BlurEffect(r, r, TileMode.Decal)
                            } else {
                                null
                            }
                        },
                ) {
                    saveable.SaveableStateProvider(screen.key) {
                        when (screen) {
                            Screen.Covers -> CoverFlowScreen(
                                state = state,
                                connection = connection,
                                art = link.art,
                                active = active,
                                actions = actions,
                                haptics = haptics,
                                requestArt = link::requestArt,
                                prefetchArt = link::prefetchArt,
                                onEnter = { cover -> push(Screen.Album(cover)) },
                                onSwipe = { kind ->
                                    haptics.strong()
                                    link.requestCollection(kind)
                                    push(Screen.Collection(kind))
                                },
                            )

                            is Screen.Collection -> CollectionScreen(
                                kind = screen.kind,
                                collection = link.collections[screen.kind],
                                loading = link.collectionsLoading[screen.kind] == true,
                                state = state,
                                art = link.art,
                                active = active,
                                actions = actions,
                                haptics = haptics,
                                requestArt = link::requestArt,
                                prefetchArt = link::prefetchArt,
                                onEnter = { cover -> push(Screen.Album(cover)) },
                                onBack = { pop() },
                            )

                            is Screen.Album -> AlbumScreen(
                                cover = screen.cover,
                                album = link.albums[AlbumRequest.keyFor(screen.cover)],
                                state = state,
                                art = link.art,
                                artColors = link.artColors,
                                active = active,
                                actions = actions,
                                haptics = haptics,
                                requestArt = link::requestArt,
                                requestAlbum = link::requestAlbum,
                                onPlay = { track, alreadyPlaying ->
                                    if (!alreadyPlaying) link.play(track)
                                    push(Screen.NowPlaying)
                                },
                            )

                            Screen.NowPlaying -> NowPlayingScreen(
                                state = state,
                                art = link.art,
                                active = active,
                                actions = actions,
                                haptics = haptics,
                                requestArt = link::requestArt,
                                onToggle = link::togglePlay,
                                onNext = link::next,
                                onPrevious = link::previous,
                                onVolume = link::changeVolume,
                                onSeek = link::seekTo,
                                onBack = { pop() },
                                onLyrics = { goTo(Screen.Lyrics) },
                                onRate = { goTo(Screen.Rate) },
                                starting = starting,
                                startingTrack = startingTrack,
                                artColors = link.artColors,
                            )

                            Screen.Lyrics -> LyricsScreen(
                                state = state,
                                lyrics = link.lyrics,
                                art = link.art,
                                active = active,
                                actions = actions,
                                haptics = haptics,
                                requestLyrics = link::requestLyrics,
                                onRate = { goTo(Screen.Rate) },
                                requestArt = link::requestArt,
                            )

                            Screen.Rate -> RateScreen(
                                state = state,
                                ratings = link.ratings,
                                art = link.art,
                                active = active,
                                actions = actions,
                                haptics = haptics,
                                requestArt = link::requestArt,
                                requestRating = link::requestRating,
                                onSave = link::setRating,
                                // After "Saved ✓" — unless Back already left the page.
                                onDone = { if (stack.last() == Screen.Rate) pop() },
                            )
                        }
                    }
                }
            }
            NoticeOverlay(notice, onDismiss = link::dismissNotice)
            if (isSystemAmbient) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - Tuning.DIM_FRACTION)))
            }
        }
    }
}
