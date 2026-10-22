package com.coverflow.mobile

import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.service.media.MediaBrowserService
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.coverflow.shared.NO_QUEUE_ID
import com.coverflow.shared.Paths
import com.coverflow.shared.TextMatch
import com.coverflow.shared.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Starts a song picked on the watch in the app it was played in.
 *
 * Each request gets time to work before anything else is sent, and as soon as the app switches
 * songs, that's its answer: nothing more is sent (asking again while an app is still starting a
 * song only interrupts it, which is why picks stopped working in YouTube Music and Apple Music
 * at one point). The ways, in order:
 *
 *  1. **The app's session**, if it has one: skip to the song in its queue, or play it if it's
 *     already loaded; otherwise a search (what voice assistants and Android Auto send, and what
 *     YouTube Music and Apple Music take), or the app's own id or link for the song (remembered
 *     from when it played). Whichever worked last time for the app goes first.
 *  2. **Its media service**, when it isn't running: connect the way Android Auto does and ask as
 *     in 1, quietly in the background.
 *  3. **Open the app with the song**, then check. Android only lets an app in the background open
 *     another app if it's linked to a companion device (the watch, linked in the phone app) or
 *     may "display over other apps".
 *  4. **Wake it** when it can't be opened: the system's Play key (when it was the last app
 *     playing and nothing else has a session) or its Play button, then ask as in 1. If it only
 *     resumes its old song, it's paused again: better silence than the wrong song.
 *  5. Nothing worked: the watch is told, with what would help.
 *
 * YouTube Music ignores requests through its controls other than its queue (only Google's own
 * apps may send them), so it's opened with the song's own link instead ([startYouTubeMusic]).
 *
 * A newer pick cancels one still in progress. Everything runs on the main thread, as
 * [MediaHub] does.
 */
object Launcher {
    private const val TAG = "CoverFlowLauncher"

    /** How long an app gets to show the song playing after each request (ms). */
    private const val VERIFY_MS = 3_000L

    /**
     * How long it gets after a search (ms): streaming apps (YouTube Music, Apple Music) look the
     * song up online first, which can take several seconds.
     */
    private const val VERIFY_SEARCH_MS = 9_000L

    /** How long to wait for a woken or opened app's media session to appear (ms). */
    private const val SESSION_WAIT_MS = 6_000L

    /** How long a woken app gets to settle (it may first resume what it played last). */
    private const val SETTLE_MS = 1_200L

    /** How long an opened app gets to find and start the song itself (ms). */
    private const val OPEN_PLAY_MS = 12_000L

    /** How long an opened app may show nothing playing before it's asked through its controls (ms). */
    private const val IDLE_AFTER_OPEN_MS = 5_000L

    /** How long what an opened app plays must stay the same to count as its choice (ms). */
    private const val SETTLED_MS = 2_000L

    /** The whole attempt, at most (the watch shows "Starting…" meanwhile). */
    private const val TOTAL_MS = 50_000L

    /** How many of a song's YouTube Music matches are opened, at most, when one plays another song. */
    private const val MAX_LINKS = 3

    /**
     * How long to wait for a song's YouTube Music link to be looked up online (ms): matching a
     * song it hasn't seen before can take Songlink a while. (Songs are looked up ahead of time
     * while they play, so this is rare.)
     */
    private const val LOOKUP_MS = 22_000L

    /** How long YouTube Music gets to play a song after being opened with its link (ms; it may have to start up first). */
    private const val LINK_PLAY_MS = 20_000L

    /**
     * How long it may leave the song from a link loaded but not playing before play is pressed
     * (ms), and how many times play is pressed (each time another way, this far apart).
     */
    private const val LINK_PAUSED_MS = 5_000L
    private const val LINK_NUDGES = 3

    /** The song must play this long (ms) after its link opens before it counts as playing. */
    private const val LINK_CONFIRM_MS = 1_200L

    private var job: Job? = null

    /**
     * What happened on the last try, step by step, in plain words (shown on the phone screen, so
     * a song that won't start can be tracked down).
     */
    private val _lastTry = MutableStateFlow<List<String>>(emptyList())
    val lastTry: StateFlow<List<String>> = _lastTry.asStateFlow()

    /** When the current pick started (for the times on its steps). */
    private var startedAt = 0L

    private fun note(text: String) {
        Log.i(TAG, text)
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        _lastTry.value = (_lastTry.value + "${"%.1f".format(Locale.ROOT, seconds)} s  $text").takeLast(40)
        appCtx?.let { Prefs.setLastTry(it, _lastTry.value) }
    }

    /** For keeping the steps across restarts of the phone app. */
    private var appCtx: Context? = null

    /** Show the last try's steps after the phone app was restarted. */
    fun restoreLastTry(ctx: Context) {
        if (_lastTry.value.isEmpty()) _lastTry.value = Prefs.lastTry(ctx)
    }

    /** Play [track] in [pkg] (from the main thread). */
    fun play(ctx: Context, pkg: String, track: Track) {
        val app = ctx.applicationContext
        appCtx = app
        job?.cancel()
        job = AppScope.launch(Dispatchers.Main) {
            _lastTry.value = emptyList()
            startedAt = SystemClock.elapsedRealtime()
            note("“${track.title}” by ${track.artist.ifBlank { "?" }} in ${Players.label(app, pkg)}")
            val browser = BrowserHolder()
            triedLinks.clear()
            openedHidden = false
            // (However the pick ends, a newer pick cancelling it included, the browser is let go.)
            try {
                val result = try {
                    withTimeoutOrNull(TOTAL_MS) {
                        if (pkg == YOUTUBE_MUSIC) startYouTubeMusic(app, pkg, track, browser) else start(app, pkg, track, browser)
                    } ?: Outcome.NOTHING
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't play in $pkg", e)
                    Outcome.NOTHING
                }
                // Nothing yet: it may still be on its way (an app opened behind the lock screen, or
                // one slow to load the song, can start it well after being asked), so it's watched a
                // while longer before the watch is told it didn't start.
                val outcome = if (result != Outcome.PLAYING && !MediaHub.isPlayingFrom(pkg) && startsLate(app, pkg, track)) {
                    Outcome.PLAYING
                } else {
                    result
                }
                note(
                    when {
                        outcome == Outcome.PLAYING -> "Playing."
                        MediaHub.isPlayingFrom(pkg) -> "Ended playing something else."
                        else -> "Nothing played."
                    },
                )
                browser.releaseLater()
                lastTitle = track.title
                if (outcome != Outcome.PLAYING) {
                    report(app, pkg)
                } else if (openedHidden && screenHidden(app)) {
                    toBackground(app, pkg)
                }
            } finally {
                browser.release() // (a no-op once releaseLater has it)
            }
        }
    }

    /** An app was opened during this pick while the phone was locked or its screen off. */
    private var openedHidden = false

    private fun screenHidden(ctx: Context): Boolean =
        phoneLocked(ctx) || ctx.getSystemService(PowerManager::class.java)?.isInteractive == false

    /**
     * An app opened behind the lock screen stays on top there, and while it's on top, many (YouTube
     * Music among them) don't put up their media notification, and with it the lock-screen and
     * watch controls. So once the song plays, the home screen is brought up behind the lock screen:
     * the app carries on playing in the background, puts its notification up, and unlocking the
     * phone shows the home screen rather than the app.
     */
    private suspend fun toBackground(ctx: Context, pkg: String) {
        delay(1_500) // let the song get going first
        note("Sending it to the background behind the lock screen, so it puts up its notification.")
        try {
            startFromBackground(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            note("Android didn't let the home screen come up (${e.javaClass.simpleName}).")
            return
        }
        // It should carry on playing; if it paused on going to the background, it's played again.
        delay(2_000)
        val c = MediaHub.controllerFor(pkg)
        if (c != null && c.playbackState?.state == PlaybackState.STATE_PAUSED) {
            note("It paused on going to the background: pressing play.")
            runCatching { c.transportControls.play() }
        }
        if (!MediaListenerService.isConnected) return
        val end = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < end && MediaListenerService.mediaNotifications(pkg).isEmpty()) delay(250)
        note(if (MediaListenerService.mediaNotifications(pkg).isNotEmpty()) "Its notification is up." else "Its notification still isn't showing.")
    }

    /** How long a pick that didn't start yet is still watched (ms), and with the phone locked. */
    private const val LATE_MS = 15_000L
    private const val LATE_LOCKED_MS = 25_000L

    /** Whether [track] starts playing in [pkg] within a little while (nothing more is sent to it). */
    private suspend fun startsLate(ctx: Context, pkg: String, track: Track): Boolean {
        val wait = if (phoneLocked(ctx)) LATE_LOCKED_MS else LATE_MS
        note("Watching ${wait / 1000} s more in case it's still starting the song.")
        val end = System.currentTimeMillis() + wait
        var since = 0L
        while (System.currentTimeMillis() < end) {
            val now = System.currentTimeMillis()
            val playing = MediaHub.controllersFor(pkg).filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            when {
                playing.any { c -> MediaHub.trackOf(c.metadata)?.let { likelyThe(it, track) || sameLength(it, track) } == true } -> {
                    if (since == 0L) since = now
                    if (now - since >= LINK_CONFIRM_MS) {
                        note("It started the song after all.")
                        winner = winner ?: "late"
                        return true
                    }
                }
                // It started something else by itself: that's its answer.
                playing.isNotEmpty() -> {
                    since = 0L
                    if (MediaHub.trackOf(playing.first().metadata) != null) return false
                }
                else -> since = 0L
            }
            delay(250)
        }
        return false
    }

    private enum class Outcome {
        /** The right song is playing. */
        PLAYING,

        /** The app reacted (it's playing something) but not the picked song. */
        OTHER_SONG,

        /** The app didn't react at all. */
        NOTHING,
    }

    /**
     * YouTube Music takes the song in its queue, or already loaded, through its media controls,
     * but ignores other "play this song" requests from apps other than Google's own. What it
     * always takes is the song's own link: opened with it, it plays it. So:
     *
     *  1. If it's running: the song in its queue, or already loaded.
     *  2. Open it with the song's link: the video id YouTube Music gave for the song when it
     *     played or queued it, or one looked up online meanwhile ([YouTubeMusicLinks]).
     *  3. Anything left, as for other apps: a search through its controls (in case it takes
     *     one after all), opening it, waking it when it can't be opened.
     */
    private suspend fun startYouTubeMusic(ctx: Context, pkg: String, track: Track, browser: BrowserHolder): Outcome {
        winner = null
        pausedWrongSong = false
        openedIdle = false
        noLink = null
        linkOpened = false
        skipLoaded = false
        val canOpen = canOpenApps(ctx)
        // Find the song's video meanwhile, if it wasn't found ahead of time (online, on its own:
        // it's remembered for next time even if this pick doesn't need it).
        val known = YouTubeMusicLinks.known(ctx, track)
        val lookup = if (known == null && canOpen) YouTubeMusicLinks.lookUp(ctx, track) else null
        var queueTried = false

        // 1. Its controls, if it's running: the song in its queue, or already loaded.
        MediaHub.controllerFor(pkg)?.let { session ->
            note("It's running: looking for the song in its queue.")
            if (askSession(ctx, pkg, track, session, woken = false, exact = false, search = false) == Outcome.PLAYING) {
                return done(ctx, pkg, METHOD_SESSION)
            }
            queueTried = true
        }

        // 2. Open it with the song's link: its best match first, then, if that turns out to be
        // another song (one YouTube Music can't play here starts something else instead), the next.
        if (canOpen) {
            var videos = YouTubeMusicLinks.knownAll(ctx, track)
            if (videos.isNotEmpty()) {
                note("Its YouTube Music links, best first: ${videos.joinToString(", ")}.")
            } else {
                videos = lookup?.let { pending ->
                    if (!pending.isCompleted) note("Looking up the song's YouTube Music link (the first time can take several seconds).")
                    val found = withTimeoutOrNull(LOOKUP_MS) { pending.await() }
                    if (found?.video == null) {
                        noLink = found?.whyNot ?: "the lookup took too long"
                        note("Couldn't find its YouTube Music link ($noLink).")
                        found?.detail?.takeIf { it.isNotBlank() }?.let { note("Lookup: $it.") }
                    } else {
                        note("Found ${found.videos.size} match(es): ${found.videos.joinToString(", ")} (${found.detail}).")
                    }
                    found?.videos
                }.orEmpty()
            }
            var opened = 0
            while (videos.isNotEmpty() && opened < MAX_LINKS) {
                val video = videos.first()
                opened++
                linkWrong = false
                if (openLink(ctx, pkg, track, video) == Outcome.PLAYING) return done(ctx, pkg, METHOD_LINK)
                // Only a link that played another song is passed over for the next; one that just
                // didn't start isn't the link's fault (more requests would only interrupt it).
                if (!linkWrong) break
                videos = YouTubeMusicLinks.knownAll(ctx, track).filter { it != video }
                if (videos.isEmpty() && opened < MAX_LINKS) {
                    note("Looking the song up again.")
                    val again = withTimeoutOrNull(LOOKUP_MS) { YouTubeMusicLinks.lookUp(ctx, track).await() }
                    videos = again?.videos.orEmpty().filter { it != video }
                    again?.detail?.takeIf { it.isNotBlank() }?.let { note("Lookup: $it.") }
                }
                if (videos.isNotEmpty()) note("Trying its next match.")
            }
            if (opened > 0) {
                linkOpened = true
                return if (MediaHub.isPlayingFrom(pkg)) Outcome.OTHER_SONG else Outcome.NOTHING
            }
        } else {
            note("It can't be opened from the background with the song's link (tap Link watch in Cover Display on the phone).")
        }

        // 3. The rest, as for other apps.
        return start(ctx, pkg, track, browser, queueTried = queueTried)
    }

    /**
     * Open YouTube Music with [video]'s link, and wait for it to play: the song, or whatever it
     * settles on for the link (its title on YouTube can differ). If it loads the song but leaves
     * it paused, play is pressed once.
     */
    private suspend fun openLink(ctx: Context, pkg: String, track: Track, video: String): Outcome {
        val link = YouTubeMusicLinks.watchLink(video)
        triedLinks += link
        // What it would play if it ignored the link: what it has loaded, or played last.
        val before = MediaHub.controllerFor(pkg)?.let { MediaHub.trackOf(it.metadata) } ?: MediaHub.lastSongFrom(pkg)
        note("Opening the song's YouTube Music link ($link).")
        if (phoneLocked(ctx)) note("The phone is locked: Android opens apps behind the lock screen, and YouTube Music may wait until it's unlocked.")
        try {
            if (screenHidden(ctx)) openedHidden = true
            startFromBackground(ctx, view(pkg, link))
        } catch (e: Exception) {
            note("Android didn't let it open (${e.javaClass.simpleName}).")
            return Outcome.NOTHING
        }
        val start = System.currentTimeMillis()
        val end = start + LINK_PLAY_MS
        var seen: String? = null
        var seenSince = 0L
        // It may open on the song without starting it (often it has no media controls until it
        // plays): then play is pressed, a few seconds apart, each time a different way.
        var nudges = 0
        var lastNudgeAt = 0L
        var usedControls = false
        var usedButton = false
        var rightSince = 0L
        while (System.currentTimeMillis() < end) {
            val sessions = MediaHub.controllersFor(pkg)
            val now = System.currentTimeMillis()
            // The song itself, really playing for a moment: a song it can't play here shows as
            // loading (or for an instant), then it says it's unavailable and moves on to another.
            if (sessions.any { it.playbackState?.state == PlaybackState.STATE_PLAYING && sameSong(MediaHub.trackOf(it.metadata), track) }) {
                if (rightSince == 0L) rightSince = now
                if (now - rightSince >= LINK_CONFIRM_MS) {
                    winner = "link"
                    return Outcome.PLAYING
                }
            } else {
                rightSince = 0L
            }
            if (nudges < LINK_NUDGES && now - start > LINK_PAUSED_MS * (nudges + 1) && sessions.none { isPlaying(it.playbackState?.state) }) {
                nudges++
                lastNudgeAt = now
                // Its controls only once they show the song (else play would bring back the last one).
                val paused = sessions.firstOrNull { likelyThe(MediaHub.trackOf(it.metadata), track) }
                when {
                    paused != null && !usedControls -> {
                        usedControls = true
                        note("It opened the song but didn't start it: pressing play on its controls.")
                        runCatching { paused.transportControls.play() }
                    }
                    !usedButton && sendPlayButton(ctx, pkg) -> {
                        usedButton = true
                        note("It opened the song but didn't start it: pressing its Play button.")
                    }
                    MediaHub.otherSessionPackages(pkg).isEmpty() -> {
                        // Nothing else has music controls, so the system's Play key goes to it.
                        note("Pressing the system's Play key.")
                        ctx.getSystemService(AudioManager::class.java)?.let { am ->
                            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                        }
                    }
                }
            }
            val song = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { MediaHub.trackOf(it.metadata) }
            when {
                song == null -> seen = null
                song.title != seen -> {
                    seen = song.title
                    seenSince = now
                }
                now - seenSince >= SETTLED_MS && (before == null || !sameSong(song, before)) && (likelyThe(song, track) || sameLength(song, track)) -> {
                    note("It's playing “${song.title}” for the link.")
                    winner = "link"
                    return Outcome.PLAYING
                }
                // Another song started right after play was pressed: the press brought back what
                // it had before. Stop it and keep waiting for the song.
                now - seenSince >= SETTLED_MS && lastNudgeAt != 0L && seenSince >= lastNudgeAt && before != null && sameSong(song, before) -> {
                    note("Pressing play brought back “${song.title}”: paused it.")
                    sessions.firstOrNull { isPlaying(it.playbackState?.state) }?.let { runCatching { it.transportControls.pause() } }
                    seen = null
                }
                // Another song settled in by itself: it couldn't play this one here (it says so,
                // and starts something else). Stop it, and never open this link for the song again.
                now - seenSince >= SETTLED_MS && (before == null || !sameSong(song, before)) -> {
                    note("It played “${song.title}” instead (it couldn't play this link): passing over that link.")
                    YouTubeMusicLinks.forget(ctx, track, video)
                    linkWrong = true
                    sessions.firstOrNull { isPlaying(it.playbackState?.state) }?.let { runCatching { it.transportControls.pause() } }
                    return Outcome.NOTHING
                }
            }
            delay(250)
        }
        // The last song it played is on (pressing play brought it back): better silence than that.
        MediaHub.controllersFor(pkg).firstOrNull { isPlaying(it.playbackState?.state) }?.let { c ->
            val playing = MediaHub.trackOf(c.metadata)
            if (playing != null && !likelyThe(playing, track) && !sameLength(playing, track)) {
                note("Paused “${playing.title}” again: better silence than the wrong song.")
                runCatching { c.transportControls.pause() }
            }
        }
        note("It opened, but didn't play the song.")
        return Outcome.NOTHING
    }

    /** Press Play on [pkg]'s own media button receiver (as a headphone button would); false if it has none. */
    private fun sendPlayButton(ctx: Context, pkg: String): Boolean {
        val receivers = try {
            ctx.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0)
        } catch (e: Exception) {
            emptyList()
        }
        if (receivers.isEmpty()) return false
        for (r in receivers) {
            val component = ComponentName(r.activityInfo.packageName, r.activityInfo.name)
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(component)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
                runCatching { ctx.sendBroadcast(intent) }
            }
        }
        return true
    }

    private fun phoneLocked(ctx: Context): Boolean =
        ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Links already opened during this pick (not opened again). */
    private val triedLinks = HashSet<String>()

    /** The song in the app's queue, or loaded, was already asked for during this pick (not asked again). */
    private var skipLoaded = false

    /** Why no YouTube Music link was found for the song (for [report]), or null. */
    private var noLink: String? = null

    /** YouTube Music was opened with the song's link but didn't play it (for [report]). */
    private var linkOpened = false

    /** The link just opened played another song (so the next match is tried). */
    private var linkWrong = false

    private suspend fun start(ctx: Context, pkg: String, track: Track, browser: BrowserHolder, queueTried: Boolean = false): Outcome {
        val canOpen = canOpenApps(ctx)
        val method = Prefs.playMethod(ctx, pkg)
        val wasPlaying = MediaHub.isPlayingFrom(pkg)
        // What it played last before this pick: if waking it only brings that back, it's paused.
        val oldSong = MediaHub.controllerFor(pkg)?.let { MediaHub.trackOf(it.metadata) } ?: MediaHub.lastSongFrom(pkg)
        var reacted = false
        if (!queueTried) {
            winner = null
            pausedWrongSong = false
            openedIdle = false
        }
        skipLoaded = queueTried

        // 1. The app's session: the song in its queue or already loaded, then a search (what
        // Apple Music and most others take from other apps) and its own id or link for the
        // song. Apps that only ever played a pick when opened with it are only asked for their
        // queue here, if they can be opened.
        MediaHub.controllerFor(pkg)?.let { session ->
            note("It has its media controls open: asking them.")
            val onlyQueue = canOpen && method == METHOD_OPEN
            when (askSession(ctx, pkg, track, session, woken = false, exact = !onlyQueue, search = !onlyQueue)) {
                Outcome.PLAYING -> return done(ctx, pkg, METHOD_SESSION)
                Outcome.OTHER_SONG -> reacted = true
                Outcome.NOTHING -> Unit
            }
        }

        skipLoaded = false // asked for again once the app has been woken or opened

        // 2. No session (the app isn't running): its media service, the way Android Auto and
        // the early versions of Cover Flow did it, quietly in the background.
        if (!reacted && MediaHub.controllerFor(pkg) == null) {
            wakeWithBrowser(ctx, pkg, browser)?.let { woken ->
                when (askSession(ctx, pkg, track, woken, woken = true, exact = true, search = true)) {
                    Outcome.PLAYING -> return done(ctx, pkg, METHOD_SESSION)
                    Outcome.OTHER_SONG -> reacted = true
                    Outcome.NOTHING -> Unit
                }
            }
        }

        // 3. Open the app with the song.
        var opened: MediaController? = null
        if (canOpen && !reacted) {
            // What it would play if it ignored the request: what it has loaded, or played last.
            val before = MediaHub.controllerFor(pkg)?.let { MediaHub.trackOf(it.metadata) } ?: MediaHub.lastSongFrom(pkg)
            opened = openApp(ctx, pkg, track)
            if (opened != null) {
                when (afterOpening(ctx, pkg, track, opened, before)) {
                    Outcome.PLAYING -> return done(ctx, pkg, METHOD_OPEN)
                    Outcome.OTHER_SONG -> reacted = true
                    Outcome.NOTHING -> Unit
                }
            }
        }

        // 4. It couldn't be opened: wake it with the Play key or its Play button, and ask.
        if (opened == null && !reacted) {
            val woken = wakeWithPlayKey(ctx, pkg) ?: wakeWithPlayButton(ctx, pkg)
            if (woken != null) {
                when (askSession(ctx, pkg, track, woken, woken = true, exact = true, search = true)) {
                    Outcome.PLAYING -> return done(ctx, pkg, METHOD_SESSION)
                    Outcome.OTHER_SONG -> reacted = true
                    Outcome.NOTHING -> Unit
                }
                // It only resumed what it played before: better silence than the wrong song. (It
                // gets a few more seconds first, in case the song is still on its way, and only
                // its old song is paused: anything else is its pick for the search.)
                if (!wasPlaying) {
                    val end = System.currentTimeMillis() + 4_000L
                    while (System.currentTimeMillis() < end && !sessionsOf(pkg, woken).any { rightSongPlaying(it, track) }) delay(250)
                    (MediaHub.controllerFor(pkg) ?: woken).let { c ->
                        val playing = MediaHub.trackOf(c.metadata)
                        if (rightSongPlaying(c, track)) return done(ctx, pkg, METHOD_SESSION)
                        if (isPlaying(c.playbackState?.state) && oldSong != null && sameSong(playing, oldSong)) {
                            note("It only resumed its last song: paused it again.")
                            runCatching { c.transportControls.pause() }
                            pausedWrongSong = true
                        }
                    }
                }
            }
        }
        return if (reacted || MediaHub.isPlayingFrom(pkg)) Outcome.OTHER_SONG else Outcome.NOTHING
    }

    private const val METHOD_SESSION = "session"
    private const val METHOD_OPEN = "open"

    /** YouTube Music played the song from its link. */
    private const val METHOD_LINK = "link"

    /** The attempt that got the song playing ("already" if it was playing it), for [done]. */
    private var winner: String? = null

    /** A woken app only resumed something else, and was paused again (for [report]). */
    private var pausedWrongSong = false

    /**
     * The app was opened with the song: it runs its own search, which takes a few seconds, so
     * this waits for that rather than asking again. Whatever it settles on playing is its best
     * match for the song, unless it's simply what it had before ([before]); then the song may
     * still be in its queue.
     */
    private suspend fun afterOpening(
        ctx: Context,
        pkg: String,
        track: Track,
        opened: MediaController,
        before: Track?,
    ): Outcome {
        val start = System.currentTimeMillis()
        val end = start + OPEN_PLAY_MS
        var seen: String? = null
        var seenSince = 0L
        var anything = false
        while (System.currentTimeMillis() < end) {
            // Nothing playing at all after a while: it's probably just showing the song.
            if (!anything && System.currentTimeMillis() - start > IDLE_AFTER_OPEN_MS) break
            val sessions = sessionsOf(pkg, opened)
            if (sessions.any { rightSongPlaying(it, track) }) {
                winner = "already"
                return Outcome.PLAYING
            }
            val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            if (sessions.any { isPlaying(it.playbackState?.state) }) anything = true
            val song = playing?.let { MediaHub.trackOf(it.metadata) }
            val now = System.currentTimeMillis()
            when {
                song == null -> seen = null
                song.title != seen -> {
                    seen = song.title
                    seenSince = now
                }
                now - seenSince >= SETTLED_MS -> {
                    if (before != null && sameSong(song, before)) break // it only resumed what it had
                    note("It chose “${song.title}” for the search.")
                    winner = "choice"
                    return Outcome.PLAYING
                }
            }
            delay(250)
        }
        if (!anything) {
            note("It opened but didn't play anything (it may just be showing the song).")
            openedIdle = true
        } else {
            note("It only resumed its last song.")
        }
        // Now that it's open, its media controls may take the song: by its queue, its own id or
        // link, or a search.
        val outcome = askSession(ctx, pkg, track, MediaHub.controllerFor(pkg) ?: opened, woken = false, exact = true, search = true)
        if (outcome == Outcome.PLAYING) {
            openedIdle = false
            return Outcome.PLAYING
        }
        return if (MediaHub.isPlayingFrom(pkg)) Outcome.OTHER_SONG else Outcome.NOTHING
    }

    /** The opened app didn't play anything by itself (for [report]). */
    private var openedIdle = false

    /** The song of the last try (for [report]). */
    private var lastTitle = ""

    /** [pkg]'s sessions, or [fallback] (e.g. one from a media browser) while it has none listed. */
    private fun sessionsOf(pkg: String, fallback: MediaController?): List<MediaController> =
        MediaHub.controllersFor(pkg).ifEmpty { listOfNotNull(fallback) }

    /**
     * The song is playing. Remember how, if it tells us something: exact requests the session
     * took mean it takes them; anything that took opening the app means that's the way.
     */
    private fun done(ctx: Context, pkg: String, step: String): Outcome {
        val request = winner?.takeIf { it == "id" || it == "uri" || it == "search" }
        when {
            step == METHOD_LINK -> Prefs.setPlayMethod(ctx, pkg, METHOD_LINK)
            step == METHOD_OPEN && request == null -> Prefs.setPlayMethod(ctx, pkg, METHOD_OPEN)
            request != null -> {
                Prefs.setPlayMethod(ctx, pkg, METHOD_SESSION)
                Prefs.setPlayRequest(ctx, pkg, request)
            }
        }
        Log.i(TAG, "Playing (step $step, by ${winner ?: "?"})")
        return Outcome.PLAYING
    }

    // ---- Asking a session ------------------------------------------------------------------

    /** A way of asking, of a [kind] ("resume", "queue", "id", "uri" or "search"); [queueId] is the queue item it skips to, if it does. */
    private class Attempt(val kind: String, val detail: String = "", val queueId: Long? = null, val run: (MediaController) -> Unit)

    /**
     * Ask [pkg]'s session for [track] every way it supports, checking after each. [session] is
     * used until the app's session shows up among the phone's sessions (one from a media
     * browser connection can come first).
     */
    private suspend fun askSession(
        ctx: Context,
        pkg: String,
        track: Track,
        session: MediaController,
        woken: Boolean,
        exact: Boolean,
        search: Boolean,
    ): Outcome {
        fun current() = MediaHub.controllerFor(pkg) ?: session

        if (woken) delay(SETTLE_MS)
        if (sessionsOf(pkg, session).any { rightSongPlaying(it, track) }) {
            winner = "already"
            return Outcome.PLAYING
        }

        for (attempt in attempts(ctx, pkg, track, current(), exact, search)) {
            val c = current()
            val before = Snapshot.of(c)
            note(
                when (attempt.kind) {
                    "resume" -> "Pressing play on it (it's already loaded)."
                    "queue" -> "Skipping to it in its queue."
                    "id" -> "Asking for it by the app's id (${attempt.detail})."
                    "uri" -> "Asking for its link (${attempt.detail})."
                    else -> "Asking the app to search for it."
                },
            )
            try {
                attempt.run(c)
            } catch (e: Exception) {
                Log.w(TAG, "  ${attempt.kind} failed", e)
                continue
            }
            // Each request gets time to work before the next is sent, and once the app switches
            // to another song that's its answer: no more requests (sending more while it's still
            // starting one only interrupts it). Ways it ignores entirely are passed over.
            val wait = if (attempt.kind == "search") VERIFY_SEARCH_MS else VERIFY_MS
            val outcome = verify(pkg, track, session, before, attempt.queueId, wait)
            if (attempt.kind == "id") Prefs.setIgnoresIds(ctx, pkg, outcome == Outcome.NOTHING)
            when (outcome) {
                Outcome.PLAYING -> {
                    winner = attempt.kind
                    return Outcome.PLAYING
                }
                Outcome.OTHER_SONG -> {
                    val now = MediaHub.trackOf(current().metadata)
                    note("It switched to “${now?.title ?: "?"}”: taking that as its pick.")
                    winner = attempt.kind
                    return Outcome.PLAYING
                }
                Outcome.NOTHING -> Unit
            }
        }
        return Outcome.NOTHING
    }

    /**
     * The ways [c] can be asked for [track]: what every app takes (the song already loaded, or
     * in its queue), then, if [exact], the app's own id for the song, and if [search], a search.
     */
    private fun attempts(
        ctx: Context,
        pkg: String,
        track: Track,
        c: MediaController,
        exact: Boolean,
        search: Boolean,
    ): List<Attempt> {
        val out = ArrayList<Attempt>()
        val actions = c.playbackState?.actions ?: 0L
        // Apps that don't list what they support often still do it.
        fun supports(action: Long) = actions == 0L || actions and action != 0L

        // Already the song in the player, just not playing.
        if (!skipLoaded && sameSong(MediaHub.trackOf(c.metadata), track)) {
            out += Attempt("resume") { it.transportControls.play() }
        }

        // In its queue.
        // (Tried even if the app doesn't list it: many take it anyway.)
        val queueId = if (skipLoaded) null else queueIdOf(c, track)
        if (queueId != null) {
            out += Attempt("queue", "$queueId", queueId) { it.transportControls.skipToQueueItem(queueId) }
        }

        // Then a search, as voice assistants ask (tried even if not listed), and the app's own id
        // or link for the song (remembered from when it played). Search comes first: it's what
        // most apps take from other apps, while many ignore ids handed back to them; whichever
        // worked last time for this app goes first.
        val requests = ArrayList<Attempt>()
        if (search) {
            requests += Attempt("search") { it.transportControls.playFromSearch(query(track), searchExtras(track)) }
        }
        if (exact) {
            val ref = Prefs.mediaRef(ctx, pkg, track.songKey)
            ref?.id?.let { id ->
                if (supports(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)) {
                    requests += Attempt("id", id) { it.transportControls.playFromMediaId(id, null) }
                }
            }
            val uri = ref?.uri ?: ref?.id?.takeIf { looksLikeUri(it) }
            if (uri != null && supports(PlaybackState.ACTION_PLAY_FROM_URI)) {
                requests += Attempt("uri", uri) { it.transportControls.playFromUri(Uri.parse(uri), null) }
            }
        }
        // The app's own id for the song is the quickest way (no search online first), so it goes
        // first unless the app is known to pass over ids; then whichever worked last time.
        val known = Prefs.playRequest(ctx, pkg)
        val idFirst = !Prefs.ignoresIds(ctx, pkg)
        out += requests.sortedBy {
            when {
                idFirst && it.kind == "id" -> 0
                it.kind == known -> 1
                else -> 2
            }
        }
        return out
    }

    private fun looksLikeUri(s: String) = Regex("^[a-z][a-z0-9+.-]*:[^\\s]+$", RegexOption.IGNORE_CASE).matches(s)

    private fun queueIdOf(c: MediaController, track: Track): Long? {
        val queue = try {
            c.queue.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        if (queue.isEmpty()) return null
        if (track.queueId != NO_QUEUE_ID) queue.firstOrNull { it.queueId == track.queueId }?.let { return it.queueId }
        return queue.firstOrNull { item ->
            val d = item.description
            sameSong(Track(title = d.title?.toString().orEmpty(), artist = d.subtitle?.toString().orEmpty()), track)
        }?.queueId
    }

    /**
     * Wait up to [waitMs] for the right song to play (or, after skipping to [queueId], for the
     * player to be playing that queue item); press play if it arrives paused.
     */
    private suspend fun verify(
        pkg: String,
        track: Track,
        fallback: MediaController,
        before: Snapshot,
        queueId: Long?,
        waitMs: Long = VERIFY_MS,
    ): Outcome {
        val end = System.currentTimeMillis() + waitMs
        var reacted = false
        var pressedPlay = false
        while (System.currentTimeMillis() < end) {
            for (c in sessionsOf(pkg, fallback)) {
                if (rightSongPlaying(c, track)) return Outcome.PLAYING
                val pb = c.playbackState
                if (queueId != null && pb != null && pb.activeQueueItemId == queueId && isPlaying(pb.state)) return Outcome.PLAYING
                if (sameSong(MediaHub.trackOf(c.metadata), track) && !pressedPlay) {
                    pressedPlay = true // the right song, but paused: play it
                    runCatching { c.transportControls.play() }
                }
            }
            // It switched to another song (its own pick for the request).
            val c = MediaHub.controllerFor(pkg) ?: fallback
            val title = MediaHub.trackOf(c.metadata)?.title
            if (title != null && title != before.title && isPlaying(c.playbackState?.state)) reacted = true
            if (reacted) return Outcome.OTHER_SONG
            delay(200)
        }
        return Outcome.NOTHING
    }

    private fun rightSongPlaying(c: MediaController, track: Track): Boolean =
        isPlaying(c.playbackState?.state) && sameSong(MediaHub.trackOf(c.metadata), track)

    private fun isPlaying(state: Int?) = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
        else -> false
    }

    /** The same song, allowing for versions ("Song - Remastered 2011", "Song (Live)"). */
    private fun sameSong(a: Track?, b: Track): Boolean {
        if (a == null) return false
        if (a.sameSongAs(b)) return true
        val x = TextMatch.loose(a.title)
        val y = TextMatch.loose(b.title)
        if (x.length < 3 || y.length < 3 || !(x.startsWith(y) || y.startsWith(x))) return false
        val p = TextMatch.primaryArtist(a.artist)
        val q = TextMatch.primaryArtist(b.artist)
        return p.isEmpty() || q.isEmpty() || p.contains(q) || q.contains(p)
    }

    /**
     * Whether [a] is (very likely) the picked song [b]: the same song, or the same title once
     * video words are cut away ("Song (Official Video)", "Full Video: Song | Film").
     */
    private fun likelyThe(a: Track?, b: Track): Boolean {
        if (a == null) return false
        if (sameSong(a, b)) return true
        val x = YouTubeMusicLinks.cleanTitle(a.title, a.artist)
        val y = YouTubeMusicLinks.cleanTitle(b.title, b.artist)
        val score = TextMatch.score(x, y)
        if (score >= 2) return true
        // Words of the title in common and the same length to the second: another upload of the
        // same recording, named differently.
        return score >= 1 && a.durationMs > 0 && b.durationMs > 0 && kotlin.math.abs(a.durationMs - b.durationMs) <= 2_000
    }

    /**
     * The same length to within a second and a half (both known): what YouTube Music settles on
     * right after being opened with the song's link is then the song, however it's named there
     * (another upload of it, or its title in another script).
     */
    private fun sameLength(a: Track, b: Track): Boolean =
        a.durationMs > 0 && b.durationMs > 0 && kotlin.math.abs(a.durationMs - b.durationMs) <= 1_500

    /** What a session shows, to tell whether it reacted to a request at all. */
    private data class Snapshot(val state: Int?, val title: String?, val updated: Long?) {
        companion object {
            fun of(c: MediaController) = Snapshot(
                c.playbackState?.state,
                MediaHub.trackOf(c.metadata)?.title,
                c.playbackState?.lastPositionUpdateTime,
            )
        }
    }

    private fun query(t: Track) = "${t.title} ${t.artist}".trim()

    private fun searchExtras(t: Track) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, t.title)
        if (t.artist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, t.artist)
        if (t.album.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ALBUM, t.album)
    }

    /** [pkg]'s session, once it appears (or null after [timeoutMs]). */
    private suspend fun waitForSession(pkg: String, timeoutMs: Long): MediaController? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            MediaHub.controllerFor(pkg)?.let { return it }
            delay(200)
        }
        return null
    }

    // ---- Waking the app quietly (when it can't be opened) ----------------------------------

    /**
     * The system's Play key goes to the app that played last when no other app has a session,
     * and Android starts that app if it isn't running. Anything else that starts playing
     * instead is paused again.
     */
    private suspend fun wakeWithPlayKey(ctx: Context, pkg: String): MediaController? {
        if (Prefs.lastPlayer(ctx) != pkg || MediaHub.otherSessionPackages(pkg).isNotEmpty()) return null
        val am = ctx.getSystemService(AudioManager::class.java) ?: return null
        note("Waking it with the Play key.")
        val playingBefore = MediaHub.playingControllers().map { it.packageName }.toSet()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        val session = waitForSession(pkg, SESSION_WAIT_MS)
        if (session == null) {
            MediaHub.playingControllers()
                .filter { it.packageName != pkg && it.packageName !in playingBefore }
                .forEach { runCatching { it.transportControls.pause() } }
        }
        return session
    }

    /** Keeps a media browser connection open while the app is being asked, then lets it go. */
    private class BrowserHolder {
        var browser: MediaBrowser? = null

        fun release() {
            browser?.let { runCatching { it.disconnect() } }
            browser = null
        }

        /** Give a just-started app time to settle into playing on its own before letting go. */
        fun releaseLater() {
            val b = browser ?: return
            browser = null
            AppScope.launch(Dispatchers.Main) {
                delay(15_000)
                runCatching { b.disconnect() }
            }
        }
    }

    private suspend fun wakeWithBrowser(ctx: Context, pkg: String, holder: BrowserHolder): MediaController? {
        val service = try {
            ctx.packageManager
                .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
                .firstOrNull()?.serviceInfo
        } catch (e: Exception) {
            null
        } ?: return null
        note("Waking it through its media service.")
        val component = ComponentName(service.packageName, service.name)
        // As a plain media browser first (what worked in the early versions), then the way the
        // system's media controls ask when offering to resume an app. If it didn't answer at
        // all the first time, it won't the second (that would only add seconds).
        val first = connect(ctx, component, recent = false)
        val browser = first?.browser ?: if (first != null) connect(ctx, component, recent = true)?.browser else null
        if (browser != null) {
            holder.browser = browser
            return try {
                MediaController(ctx, browser.sessionToken)
            } catch (e: Exception) {
                null
            }
        }
        // Turned away, but being asked may still have started its session.
        return waitForSession(pkg, 1_500)
    }

    /** How a media browser connection went: [browser] if connected, null if the app refused. */
    private class BrowserAnswer(val browser: MediaBrowser?)

    /** Connects to a media browser service: its answer, or null if it didn't answer in time. */
    private suspend fun connect(ctx: Context, component: ComponentName, recent: Boolean): BrowserAnswer? =
        withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine<BrowserAnswer> { cont ->
                val holder = arrayOfNulls<MediaBrowser>(1)
                val callback = object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        if (cont.isActive) cont.resumeWith(Result.success(BrowserAnswer(holder[0])))
                    }

                    override fun onConnectionFailed() {
                        if (cont.isActive) cont.resumeWith(Result.success(BrowserAnswer(null)))
                    }
                }
                val hints = if (recent) Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) } else null
                val browser = MediaBrowser(ctx, component, callback, hints)
                holder[0] = browser
                cont.invokeOnCancellation { runCatching { browser.disconnect() } }
                try {
                    browser.connect()
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.success(BrowserAnswer(null)))
                }
            }
        }

    /** Press Play on the app's own media button receiver. */
    private suspend fun wakeWithPlayButton(ctx: Context, pkg: String): MediaController? {
        if (!sendPlayButton(ctx, pkg)) return null
        note("Waking it with its Play button.")
        return waitForSession(pkg, SESSION_WAIT_MS)
    }

    // ---- Opening the app with the song -----------------------------------------------------

    /**
     * Whether Android lets us open other apps from the background: the watch is linked as a
     * companion device, or "display over other apps" is allowed.
     */
    fun canOpenApps(ctx: Context): Boolean = CompanionLink.isLinked(ctx) || Settings.canDrawOverlays(ctx)

    private suspend fun openApp(ctx: Context, pkg: String, track: Track): MediaController? {
        val pm = ctx.packageManager
        val intent = openIntents(ctx, pkg, track)
            .filter { it.dataString == null || it.dataString !in triedLinks }
            .firstOrNull { it.resolveActivity(pm) != null }
            ?: pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return null
        note(
            when {
                intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> "Opening it with a request to play the song (a search)."
                intent.dataString != null -> "Opening it with the song's own link (${intent.dataString})."
                else -> "Opening it."
            },
        )
        return try {
            if (screenHidden(ctx)) openedHidden = true
            startFromBackground(ctx, intent)
            waitForSession(pkg, SESSION_WAIT_MS + 2_000) ?: run {
                note("It didn't open, or opened without its media controls.")
                null
            }
        } catch (e: Exception) {
            note("Android didn't let it open (${e.javaClass.simpleName}).")
            null
        }
    }

    /**
     * Start an activity from the background. With "display over other apps" (and no companion
     * link), Android 15 also wants an overlay window showing at that moment, so a 1-pixel
     * see-through one is shown for a second around the start.
     */
    private suspend fun startFromBackground(ctx: Context, intent: Intent) {
        val overlay = !CompanionLink.isLinked(ctx) && Settings.canDrawOverlays(ctx)
        val wm = if (overlay) ctx.getSystemService(WindowManager::class.java) else null
        val view = if (wm != null) View(ctx) else null
        val shown = view != null && runCatching {
            wm!!.addView(
                view,
                WindowManager.LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START },
            )
        }.isSuccess
        try {
            if (shown) delay(150) // let the window show first
            ctx.startActivity(intent)
            if (shown) delay(1_000)
        } finally {
            if (shown) runCatching { wm!!.removeView(view) }
        }
    }

    /** Ways to open [pkg] straight into playing [track], most exact first. */
    private fun openIntents(ctx: Context, pkg: String, track: Track): List<Intent> {
        val out = ArrayList<Intent>()
        val ref = Prefs.mediaRef(ctx, pkg, track.songKey)
        val id = ref?.id
        // The song's own link in the app: YouTube Music plays a watch link it's opened with (its
        // id for a song is the video id), Spotify a track link.
        if (pkg == YOUTUBE_MUSIC) YouTubeMusicLinks.known(ctx, track)?.let { out += view(pkg, YouTubeMusicLinks.watchLink(it)) }
        val trackLink = listOfNotNull(ref?.uri, id).firstOrNull { it.startsWith("spotify:track:") }
        if (trackLink != null) out += view(pkg, trackLink)
        // What voice assistants send: many apps play the best match (some only show it).
        out += Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SearchManager.QUERY, query(track))
            .putExtras(searchExtras(track))
        // Any other link of the app's own for the song.
        val other = listOfNotNull(ref?.uri, id).firstOrNull { looksLikeUri(it) && it != trackLink }
        if (other != null) out += view(pkg, other)
        return out
    }

    private fun view(pkg: String, link: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val YOUTUBE_MUSIC = YouTubeMusicLinks.PACKAGE

    // ---- Telling the watch ----------------------------------------------------------------

    /**
     * Tell the watch why nothing is playing. If the app is playing something, it's what the app
     * chose (shown on the watch as usual), so there's nothing to say.
     */
    private suspend fun report(ctx: Context, pkg: String) {
        if (MediaHub.isPlayingFrom(pkg)) return
        val app = Players.label(ctx, pkg)
        val canOpen = canOpenApps(ctx)
        val text = when {
            pkg == YOUTUBE_MUSIC && linkOpened && phoneLocked(ctx) ->
                "$app was opened on “${lastTitle}” but waits for your phone to be unlocked. Unlock it and the song starts (or tap play there)."
            pkg == YOUTUBE_MUSIC && linkOpened ->
                "$app was opened on “${lastTitle}” but didn't start it. Tap play there. (The steps are listed in Cover Display on your phone.)"
            pkg == YOUTUBE_MUSIC && noLink != null ->
                "Couldn't find “${lastTitle}” on $app to open it: $noLink." +
                    (if (Prefs.youtubeApiKey(ctx) == null) " A YouTube API key in Cover Display on your phone makes the search dependable." else "")
            openedIdle ->
                "$app opened but didn't play “${lastTitle}” (it may just be showing it). Tap it there. Songs played while Cover Display runs can be started directly next time."
            pausedWrongSong && !canOpen ->
                "$app only resumes its last song when it's closed. In Cover Display on your phone, tap Link watch so $app can be opened with the song you pick."
            !canOpen -> "$app didn't start. In Cover Display on your phone, tap Link watch so it can open $app."
            else -> "$app didn't start. If your phone is locked, unlock it and try again."
        }
        WatchSync.sendToAll(ctx, Paths.PLAY_PROBLEM, text.toByteArray(Charsets.UTF_8))
    }
}
