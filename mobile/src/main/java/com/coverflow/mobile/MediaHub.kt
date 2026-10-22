package com.coverflow.mobile

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.coverflow.shared.Command
import com.coverflow.shared.NO_QUEUE_ID
import com.coverflow.shared.PlayerState
import com.coverflow.shared.TextMatch
import com.coverflow.shared.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Watches the phone's media sessions (Spotify, YouTube Music, JioSaavn, ...), turns the active
 * one into a [PlayerState], publishes it to the watch, and executes the watch's commands.
 *
 * Everything here runs on the main thread.
 */
object MediaHub {
    private const val TAG = "CoverFlowHub"
    private const val QUEUE_BEFORE = 15
    private const val QUEUE_MAX = 60

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var started = false
    private var sessionManager: MediaSessionManager? = null

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback)

    private val tracked = LinkedHashMap<MediaSession.Token, Tracked>()
    private var order: List<MediaSession.Token> = emptyList()
    private var active: MediaController? = null

    private var history: MutableList<Track> = mutableListOf()

    /** Player apps the user switched off in the phone app. */
    private var excluded: Set<String> = emptySet()
    private var lastPublished: PlayerState? = null
    private var lastFaceKey: String? = null
    /** The next song whose face background was last sent ahead ([WatchFaceArt.prepare]). */
    private var lastNextKey: String? = null

    /**
     * The last few next songs sent ahead, with the keys they went under: as a skip goes, the
     * player's queue can move on before its song does, and the one after is noted meanwhile.
     */
    private val aheadSent = ArrayDeque<Pair<Track, String>>()

    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state.asStateFlow()

    fun hasAccess(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun listenerComponent(ctx: Context) = ComponentName(ctx, MediaListenerService::class.java)

    /** Forget the recently played list (shown when a player doesn't share its queue). */
    fun clearHistory(ctx: Context) {
        val appCtx = ctx.applicationContext
        main.post {
            history.clear()
            Prefs.saveHistory(appCtx, history)
            if (started) publish(force = true)
        }
    }

    /** The user switched a player app on or off: re-pick the active player and update the watch. */
    fun refreshExclusions(ctx: Context) {
        val appCtx = ctx.applicationContext
        main.post {
            excluded = Prefs.excludedPlayers(appCtx)
            if (started) publish(force = true)
        }
    }

    /** Safe to call repeatedly and from any thread. */
    fun start(ctx: Context) {
        val appCtx = ctx.applicationContext
        main.post { startOnMain(appCtx) }
    }

    private fun startOnMain(ctx: Context) {
        app = ctx
        Region.init(ctx)
        if (started) {
            refreshSessions()
            return
        }
        if (!hasAccess(ctx)) return
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(ctx), main)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification access yet", e)
            return
        }
        sessionManager = msm
        history = Prefs.history(ctx).toMutableList()
        // Recently played songs can show on the watch with nothing playing: let their covers
        // be looked up too.
        history.forEach { ArtStore.hint(it) }
        excluded = Prefs.excludedPlayers(ctx)
        ctx.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        started = true
        refreshSessions()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessions(list.orEmpty())
    }

    /** Phone volume keys change Settings.System; republish so the watch's volume arc stays right. */
    private val volumeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = changed()
    }

    private fun refreshSessions() {
        val msm = sessionManager ?: return
        val list = try {
            msm.getActiveSessions(listenerComponent(app))
        } catch (e: SecurityException) {
            emptyList()
        }
        onSessions(list)
    }

    private fun onSessions(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        val iter = tracked.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in tokens) {
                entry.value.controller.unregisterCallback(entry.value.callback)
                iter.remove()
            }
        }
        for (c in list) {
            if (tracked.containsKey(c.sessionToken)) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
                override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
                override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = changed()
                override fun onSessionDestroyed() {
                    main.post { refreshSessions() }
                }
            }
            c.registerCallback(cb, main)
            tracked[c.sessionToken] = Tracked(c, cb)
        }
        order = list.map { it.sessionToken }
        Prefs.addSeenPlayers(app, list.map { it.packageName })
        changed()
    }

    private val publishRunnable = Runnable { publish(force = false) }

    /**
     * Something changed: republish shortly (debounced: a player's burst of callbacks as a song
     * changes, its title, state and queue, arrive within a few dozen ms of each other and go as
     * one). Short, so a new song, and the watch face's cover for it, reach the watch quickly; a
     * burst that runs on goes as two, which costs nothing much (an unchanged state isn't resent).
     */
    private fun changed() {
        main.removeCallbacks(publishRunnable)
        main.postDelayed(publishRunnable, PUBLISH_DEBOUNCE_MS)
    }

    private const val PUBLISH_DEBOUNCE_MS = 50L

    private fun pickActive(): MediaController? {
        val ordered = order.mapNotNull { tracked[it]?.controller }.filter { it.packageName !in excluded }
        val playing = ordered.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing != null) {
            notePlaying(playing.packageName, true)
            return playing
        }
        // Keep showing the last active player while it's paused, as long as it's still open.
        val present = ordered.filter { isOpen(it) }
        val prev = active?.sessionToken?.let { tracked[it]?.controller }
            ?.takeIf { it.packageName !in excluded && it in present }
        if (prev != null) return prev
        return present.firstOrNull { it.metadata != null } ?: present.firstOrNull()
    }

    // ---- Is the player app still open? ------------------------------------------------------

    /** After a player stops playing, how long it counts as open even without its notification. */
    private const val CLOSE_GRACE_MS = 10_000L

    /** While notifications can't be seen, how long a paused player still counts as open. */
    private const val PAUSED_OPEN_BLIND_MS = 3 * 60_000L

    private var rebindAskedAt = 0L

    /** Ask Android to reconnect our notification listener (at most once a minute). */
    private fun askRebind() {
        val now = SystemClock.uptimeMillis()
        if (now - rebindAskedAt < 60_000L) return
        rebindAskedAt = now
        runCatching { NotificationListenerService.requestRebind(listenerComponent(app)) }
    }

    /** Per player app: whether it was playing when last checked, and when it last was. */
    private val wasPlaying = HashMap<String, Boolean>()
    private val lastPlayingAt = HashMap<String, Long>()

    private fun notePlaying(pkg: String, playing: Boolean) {
        val now = SystemClock.uptimeMillis()
        // Playing now, or it just stopped (the moment the grace period starts from).
        if (playing || wasPlaying[pkg] == true) lastPlayingAt[pkg] = now
        wasPlaying[pkg] = playing
    }

    private fun isPlayingState(state: Int?): Boolean = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    /**
     * Whether [c]'s app is still open from the user's point of view: playing, or paused with
     * its music controls still in the notification shade.
     *
     * Many apps keep their media session alive, paused on the last song, after they're swiped
     * away, so a paused session on its own doesn't mean the app is open. What does go away when
     * an app is closed is its media notification. A stopped session, or a paused one whose
     * notification is gone, counts as nothing playing, and the watch shows the recently played
     * songs. Right after it stops playing, an app counts as open for [CLOSE_GRACE_MS] whatever
     * it reports, so skipping songs or pausing never flashes the recently played list.
     */
    private fun isOpen(c: MediaController): Boolean {
        val pkg = c.packageName
        val state = c.playbackState?.state
        if (isPlayingState(state)) {
            notePlaying(pkg, true)
            return true
        }
        notePlaying(pkg, false)
        if (state == PlaybackState.STATE_PAUSED) {
            if (MediaListenerService.isConnected) {
                if (MediaListenerService.mediaNotifications(pkg).isNotEmpty()) return true
            } else {
                // Notifications can't be seen just now (Android sometimes doesn't reconnect the
                // listener after the app is updated): ask it to, and meanwhile count a paused app
                // as open only for a while after it last played, so the recently played songs
                // still come back.
                askRebind()
                val sincePlayed = SystemClock.uptimeMillis() - (lastPlayingAt[pkg] ?: 0L)
                if (sincePlayed < PAUSED_OPEN_BLIND_MS) {
                    // Check again once that while is over.
                    main.removeCallbacks(publishRunnable)
                    main.postDelayed(publishRunnable, PAUSED_OPEN_BLIND_MS - sincePlayed + 50L)
                    return true
                }
            }
        }
        // Stopped, or paused with no notification: only open for a moment after it was playing
        // (apps briefly report "stopped" between songs, or repost their notification on pause).
        val since = SystemClock.uptimeMillis() - (lastPlayingAt[pkg] ?: 0L)
        if (since < CLOSE_GRACE_MS) {
            // Check again once the grace period is over.
            main.removeCallbacks(publishRunnable)
            main.postDelayed(publishRunnable, CLOSE_GRACE_MS - since + 50L)
            return true
        }
        return false
    }

    /** A player posted or removed its media notification (e.g. the app was closed). */
    fun mediaNotificationChanged() {
        main.post { if (started) changed() }
    }

    private fun publish(force: Boolean) {
        if (!started) return
        active = pickActive()
        var s = buildState(active)
        // The queue's next song now playing: its background went ahead under the key its queue
        // entry gave (often without the album, so not the key it plays under), and the watches
        // put that one up as it starts (seen on the watch: every skip waited for its own).
        // (By title: a queue entry's artist is often not the song's own, as "Artist • Album".)
        val cur = s.current
        val aheadKey = cur?.let { c -> aheadSent.lastOrNull { TextMatch.loose(it.first.title) == TextMatch.loose(c.title) }?.second }
        if (cur != null && aheadKey != null && aheadKey != cur.artKey) s = s.copy(aheadKey = aheadKey)
        _state.value = s
        // YouTube Music songs (playing, and recently played) get their links looked up ahead of
        // time, so picking one later starts it at once (it shares no ids of its own).
        YouTubeMusicLinks.warm(app, (listOfNotNull(s.current) + history.asReversed()).filter { it.source == YouTubeMusicLinks.PACKAGE })

        val prev = lastPublished
        val sameContent = prev != null &&
            prev.copy(positionMs = 0, capturedAt = 0, seq = 0) == s.copy(positionMs = 0, capturedAt = 0, seq = 0)
        val drift = if (prev == null) Long.MAX_VALUE else abs(prev.positionAt(s.capturedAt) - s.positionMs)
        // The watch works the position out from the last one sent, so a new one goes out as
        // soon as the player's drifts from that by more than a moment (the lyrics follow it).
        // Watch faces showing the album art hear first, in a small message of their own (the
        // state item is synced, which is slower): a new song, play, pause, a skip or a seek.
        WatchFaceArt.faceState(app, s)
        // A new song playing (or being skipped to): watch faces showing the album art get its
        // background with it (unless it was sent ahead, below). (Started first: it takes a moment
        // to make, and the watch switches once it has both.)
        val key = s.current?.artKey
        if (s.inPlay && !key.isNullOrBlank() && key != lastFaceKey) {
            lastFaceKey = key
            WatchFaceArt.songChanged(app, key, s.aheadKey)
        }
        // The next song in the queue: its background made now and sent ahead to those watches,
        // which keep it aside for the moment it starts. Whenever the next song's cover changes,
        // not only with a new cover playing: the songs of one album share theirs.
        if (s.inPlay && !key.isNullOrBlank() && s.listIsQueue && s.currentIndex >= 0) {
            val nextTrack = s.list.getOrNull(s.currentIndex + 1)
            val next = nextTrack?.artKey?.takeIf { it.isNotBlank() }
            if (next != null && next != key && next != lastNextKey) {
                lastNextKey = next
                aheadSent.addLast(nextTrack to next)
                while (aheadSent.size > 3) aheadSent.removeFirst()
                WatchFaceArt.prepare(app, next)
            }
        }
        if (force || !sameContent || drift > POSITION_DRIFT_MS) {
            lastPublished = s
            WatchSync.publishState(app, s)
        }
    }

    /** How far (ms) the player's position may drift from what the watch works out before it's resent. */
    private const val POSITION_DRIFT_MS = 300L

    // ---- State building ---------------------------------------------------------------------

    private fun buildState(c: MediaController?): PlayerState {
        val (volume, maxVolume) = readVolume(c)
        if (c == null) {
            // (Stamped like any other state, so a watch can tell it from an older one arriving late.)
            return PlayerState(
                list = history.toList(),
                currentIndex = -1,
                volume = volume,
                maxVolume = maxVolume,
                capturedAt = System.currentTimeMillis(),
                seq = SystemClock.elapsedRealtime(),
            )
        }
        val md = c.metadata
        val pb = c.playbackState
        val sourceName = Players.label(app, c.packageName)
        val current = md?.let { trackFromMetadata(it) }?.takeIf { it.title.isNotBlank() }
            ?.copy(source = c.packageName, sourceName = sourceName)

        if (current != null) {
            if (pb?.state == PlaybackState.STATE_PLAYING) Prefs.setLastPlayer(app, c.packageName)
            ArtStore.hint(current)
            playerArt(md)?.let { ArtStore.fromPlayer(app, current, it) }
            playerArtUri(md)?.let { ArtStore.offer(current.artKey, null, it) }
            recordHistory(current)
            noteLiked(c, current)
            // Remember how the player names this song, to ask for it exactly when it's picked later.
            Prefs.saveMediaRef(
                app,
                c.packageName,
                current.songKey,
                md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
            )
        }

        // Song list: the player's queue when it shares one, otherwise recently played songs.
        val queue = try {
            c.queue.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val activeQueueId = pb?.activeQueueItemId ?: MediaSession.QueueItem.UNKNOWN_ID.toLong()
        var list: List<Track>
        var currentIndex: Int
        val isQueue = queue.isNotEmpty()
        if (isQueue) {
            val all = queue.map { trackFromQueueItem(it).copy(source = c.packageName, sourceName = sourceName) }
            // The player's own ids for the songs in its queue: picked later from the recently
            // played list, they can be asked for exactly.
            Prefs.saveMediaRefs(
                app,
                c.packageName,
                queue.zip(all) { item, t -> Triple(t.songKey, item.description.mediaId, item.description.mediaUri?.toString()) },
            )
            var idx = all.indexOfFirst { it.queueId == activeQueueId && activeQueueId != NO_QUEUE_ID }
            if (idx < 0 && current != null) idx = all.indexOfFirst { it.sameSongAs(current) }
            val from = (idx - QUEUE_BEFORE).coerceAtLeast(0).coerceAtMost(maxOf(0, all.size - 1))
            list = all.subList(from, minOf(all.size, from + QUEUE_MAX)).toList()
            currentIndex = if (idx >= 0) idx - from else -1
            if (currentIndex < 0 && current != null) {
                list = listOf(current) + list
                currentIndex = 0
            } else if (currentIndex >= 0 && current != null) {
                // Prefer the richer now-playing metadata (album, duration) for the current item.
                val q = list[currentIndex]
                list = list.toMutableList().also { it[currentIndex] = current.copy(queueId = q.queueId) }
            }
        } else {
            val past = history.filterNot { it.sameSongAs(current) }
            list = if (current != null) past + current else past
            currentIndex = if (current != null) list.lastIndex else -1
        }

        val now = System.currentTimeMillis()
        val isPlaying = pb?.state == PlaybackState.STATE_PLAYING

        // When it stopped playing this song (the watch switches its covers to the recently played
        // songs once it has been paused a while). Kept while it stays paused on the same song.
        val pausedAt = if (isPlayingState(pb?.state) || current == null) {
            pausedSince.remove(c.packageName)
            0L
        } else {
            val kept = pausedSince[c.packageName]?.takeIf { it.first == current.songKey }
            kept?.second ?: run {
                val stoppedAgo = lastPlayingAt[c.packageName]?.let { SystemClock.uptimeMillis() - it } ?: 0L
                (now - stoppedAgo).also { pausedSince[c.packageName] = current.songKey to it }
            }
        }
        val position = pb?.let {
            if (isPlaying && it.lastPositionUpdateTime > 0) {
                it.position + ((SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
            } else it.position
        } ?: 0L

        return PlayerState(
            player = Players.label(app, c.packageName),
            playerPackage = c.packageName,
            current = current?.let { cur ->
                if (currentIndex >= 0) cur.copy(queueId = list[currentIndex].queueId) else cur
            },
            isPlaying = isPlaying,
            // (Loading or skipping: not playing, but not paused either. Watch faces keep the album
            // art up meanwhile, and take it down at once on a real pause.)
            between = !isPlaying && isPlayingState(pb?.state),
            seq = SystemClock.elapsedRealtime(),
            positionMs = position.coerceAtLeast(0L),
            capturedAt = now,
            speed = pb?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            list = list,
            currentIndex = currentIndex,
            listIsQueue = isQueue,
            volume = volume,
            maxVolume = maxVolume,
            // The list is the queue: the recently played songs go along too, for the watch to switch to.
            recent = if (isQueue) history.toList() else emptyList(),
            pausedAt = pausedAt,
        )
    }

    /** Per player app: the song it's paused on and when it stopped playing (wall-clock ms). */
    private val pausedSince = HashMap<String, Pair<String, Long>>()

    private fun trackFromMetadata(md: MediaMetadata): Track {
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        return Track(
            title = title,
            artist = artist,
            album = album,
            artKey = TextMatch.artKey(artist, album, title),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
            trackNumber = md.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER).toInt(),
        )
    }

    // DISPLAY_ICON is deliberately not used: players often put their app logo there.
    private fun playerArt(md: MediaMetadata?): Bitmap? {
        if (md == null) return null
        return md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
    }

    private fun playerArtUri(md: MediaMetadata?): String? {
        if (md == null) return null
        return md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
    }

    private fun trackFromQueueItem(item: MediaSession.QueueItem): Track {
        val d = item.description
        val title = d.title?.toString().orEmpty()
        var artist = d.subtitle?.toString().orEmpty()
        var album = d.description?.toString().orEmpty()
        try {
            // Some players put proper metadata keys in the extras.
            d.extras?.let { ex ->
                ex.getString(MediaMetadata.METADATA_KEY_ARTIST)?.let { artist = it }
                ex.getString(MediaMetadata.METADATA_KEY_ALBUM)?.let { album = it }
            }
        } catch (e: Exception) {
            // Extras containing the player's private Parcelables can't be read; ignore them.
        }
        val track = Track(
            title = title,
            artist = artist,
            album = album,
            artKey = TextMatch.artKey(artist, album, title),
            queueId = item.queueId,
        )
        ArtStore.hint(track)
        ArtStore.offer(track.artKey, d.iconBitmap, d.iconUri?.toString())
        return track
    }

    private fun recordHistory(current: Track) {
        val last = history.lastOrNull()
        if (last != null && last.sameSongAs(current)) {
            if (last != current.copy(queueId = NO_QUEUE_ID)) {
                history[history.lastIndex] = current.copy(queueId = NO_QUEUE_ID) // e.g. album arrived late
                Prefs.saveHistory(app, history)
            }
            return
        }
        history.removeAll { it.sameSongAs(current) }
        history.add(current.copy(queueId = NO_QUEUE_ID))
        while (history.size > Prefs.HISTORY_MAX) history.removeAt(0)
        Prefs.saveHistory(app, history)
    }

    private fun readVolume(c: MediaController?): Pair<Int, Int> {
        val info = c?.playbackInfo
        if (info != null && info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            return info.currentVolume to info.maxVolume
        }
        val am = app.getSystemService(AudioManager::class.java) ?: return 0 to 0
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) to am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    // ---- Commands from the watch ------------------------------------------------------------

    fun execute(ctx: Context, cmd: Command) {
        val appCtx = ctx.applicationContext
        main.post {
            startOnMain(appCtx)
            if (cmd.action == Command.SYNC) {
                active = pickActive()
                publish(force = true)
                return@post
            }
            if (cmd.action == Command.PLAY_TRACK) {
                cmd.track?.let { playAnywhere(it) }
                changed()
                return@post
            }
            val c = pickActive() ?: return@post
            val controls = c.transportControls
            when (cmd.action) {
                Command.TOGGLE ->
                    if (c.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
                Command.NEXT -> controls.skipToNext()
                Command.PREV -> controls.skipToPrevious()
                Command.VOLUME -> adjustVolume(c, cmd.delta)
                Command.SEEK -> {
                    val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                    val target = if (duration > 0) cmd.positionMs.coerceIn(0L, duration) else cmd.positionMs.coerceAtLeast(0L)
                    controls.seekTo(target)
                }
            }
            changed()
        }
    }

    // ---- Playing a picked song, even when nothing is playing ---------------------------------

    /** Every session [pkg] has open (an app can have more than one, some left behind). */
    fun controllersFor(pkg: String): List<MediaController> =
        order.mapNotNull { tracked[it]?.controller }.filter { it.packageName == pkg }

    /** [pkg]'s session: the one playing if any, else the first one it has open. */
    fun controllerFor(pkg: String): MediaController? {
        val all = controllersFor(pkg)
        return all.firstOrNull { isPlayingState(it.playbackState?.state) } ?: all.firstOrNull()
    }

    /** Apps other than [pkg] with a media session open (playing or not). */
    fun otherSessionPackages(pkg: String): Set<String> =
        order.mapNotNull { tracked[it]?.controller?.packageName }.filter { it != pkg }.toSet()

    /** Sessions playing right now, by app. */
    fun playingControllers(): List<MediaController> =
        order.mapNotNull { tracked[it]?.controller }.filter { isPlayingState(it.playbackState?.state) }

    /** The song [pkg] played most recently, from the recently played list. */
    fun lastSongFrom(pkg: String): Track? = history.lastOrNull { it.source == pkg }

    /** The song in a player's metadata, or null. */
    fun trackOf(md: MediaMetadata?): Track? = md?.let { trackFromMetadata(it) }?.takeIf { it.title.isNotBlank() }

    /** True once [pkg] is playing (or about to), in any of its sessions. */
    fun isPlayingFrom(pkg: String): Boolean = controllersFor(pkg).any {
        when (it.playbackState?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
            else -> false
        }
    }

    /**
     * Play [t] in the app it was last played from ([Launcher] finds a way and checks the right
     * song is the one playing).
     */
    private fun playAnywhere(t: Track) {
        val source = t.source.ifBlank { pickActive()?.packageName ?: Prefs.lastPlayer(app).orEmpty() }
        if (source.isBlank()) return
        Launcher.play(app, source, t.copy(source = source))
    }

    // ---- Likes and ratings in the player app ----------------------------------------------

    class PlayerRatingInfo(
        val likeInPlayer: Boolean = false,
        val starsInPlayer: Boolean = false,
        /** The player's own like state, when it tells us; null = unknown. */
        val liked: Boolean? = null,
        val stars: Int? = null,
    )

    /**
     * A button the player exposes to other apps: a media-session custom action ("s:<id>") or
     * an action on its media notification ("n:<title>").
     */
    class PlayerButton(val key: String, val label: String, val fromNotification: Boolean)

    /** What the phone app shows under "Like button" for the playing app. */
    class LikeSetup(
        val pkg: String,
        val appName: String,
        /** The player takes likes through the standard rating API; no button needed. */
        val ratingApi: Boolean,
        val buttons: List<PlayerButton>,
        val chosenKey: String?,
        val detectedKey: String?,
    )

    private val likeWords = listOf(
        "like", "love", "heart", "favorite", "favourite", "thumb_up", "thumbs up", "thumbup", "collection",
    )
    private val notLikeWords = listOf("dislike", "thumb_down", "thumbs down", "thumbdown", "not interested")

    /**
     * Buttons that count as a dislike: an explicit thumbs-down only. "Not interested" shapes the
     * app's recommendations rather than rating the song, so it's never pressed; under 3 stars
     * those apps get their like removed instead (full star or heart -> empty).
     */
    private val dislikeWords = listOf("dislike", "thumb_down", "thumbs down", "thumbdown")

    private fun isDislikeLabel(text: String): Boolean {
        val t = text.lowercase()
        return dislikeWords.any { it in t }
    }

    private fun isLikeLabel(text: String): Boolean {
        val t = text.lowercase()
        return likeWords.any { it in t } && notLikeWords.none { it in t }
    }

    /**
     * Whether a like button's label says the song is liked already. "Love" / "Add to Liked" /
     * "Favourite" = not yet; "Unlove" / "Remove from…" / "Favorited" = already. null = can't tell.
     */
    private fun likeLabelState(label: String): Boolean? {
        val t = label.lowercase().trim()
        return when {
            t.startsWith("add") || t.startsWith("save") -> false
            t.contains("remove") || t.startsWith("un") || t.startsWith("undo") ||
                t.contains("liked") || t.contains("loved") ||
                t.contains("favorited") || t.contains("favourited") || t.contains("saved") -> true
            likeWords.any { it in t } || dislikeWords.any { it in t } -> false
            else -> null
        }
    }

    private fun buttons(c: MediaController): List<PlayerButton> {
        val out = ArrayList<PlayerButton>()
        c.playbackState?.customActions?.forEach { a ->
            val name = a.name?.toString().orEmpty().ifBlank { a.action }
            out += PlayerButton("s:" + a.action, name, fromNotification = false)
        }
        MediaListenerService.mediaNotifications(c.packageName).firstOrNull()?.actions?.forEach { a ->
            val title = a.title?.toString().orEmpty()
            if (title.isNotBlank() && out.none { it.label.equals(title, ignoreCase = true) }) {
                out += PlayerButton("n:" + title.lowercase(), title, fromNotification = true)
            }
        }
        return out
    }

    /** The user's chosen like button for this app if it's there, else the first one that looks like one. */
    private fun likeButton(c: MediaController, all: List<PlayerButton> = buttons(c)): PlayerButton? {
        Prefs.likeButton(app, c.packageName)?.let { chosen ->
            all.firstOrNull { it.key == chosen }?.let { return it }
        }
        return all.firstOrNull { isLikeLabel(it.label) || isLikeLabel(it.key) }
    }

    private fun press(c: MediaController, b: PlayerButton): Boolean = try {
        if (!b.fromNotification) {
            val id = b.key.removePrefix("s:")
            val action = c.playbackState?.customActions?.firstOrNull { it.action == id }
            if (action != null) c.transportControls.sendCustomAction(action, null)
            action != null
        } else {
            val title = b.key.removePrefix("n:")
            val action = MediaListenerService.mediaNotifications(c.packageName).firstOrNull()
                ?.actions?.firstOrNull { it.title?.toString()?.lowercase() == title }
            action?.actionIntent?.send()
            action != null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't press ${b.label}", e)
        false
    }

    private fun userRating(c: MediaController): Rating? = try {
        c.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.takeIf { it.isRated }
    } catch (e: Exception) {
        null
    }

    /** For the phone app's "Like button" section. */
    fun likeSetup(): LikeSetup? {
        if (!started) return null
        val c = pickActive() ?: return null
        val all = buttons(c)
        val type = c.ratingType
        return LikeSetup(
            pkg = c.packageName,
            appName = Players.label(app, c.packageName),
            ratingApi = type == Rating.RATING_HEART || type == Rating.RATING_THUMB_UP_DOWN,
            buttons = all,
            chosenKey = Prefs.likeButton(app, c.packageName),
            detectedKey = all.firstOrNull { isLikeLabel(it.label) || isLikeLabel(it.key) }?.key,
        )
    }

    /** What the player can do for [track]. Players only rate the song that's playing now. */
    fun ratingInfo(track: Track): PlayerRatingInfo {
        val c = pickActive() ?: return PlayerRatingInfo()
        val playing = c.metadata?.let { trackFromMetadata(it) }
        if (playing == null || !playing.sameSongAs(track)) return PlayerRatingInfo()
        val type = c.ratingType
        val button = likeButton(c)
        val rating = userRating(c)
        val style = rating?.ratingStyle ?: -1
        val stars: Int? = if (rating != null && style == Rating.RATING_5_STARS) rating.starRating.roundToInt() else null
        return PlayerRatingInfo(
            likeInPlayer = type == Rating.RATING_HEART || type == Rating.RATING_THUMB_UP_DOWN || button != null,
            starsInPlayer = type == Rating.RATING_5_STARS,
            liked = likedIn(c),
            stars = stars,
        )
    }

    /** Whether [c]'s song is liked there (its heart, thumb or like button), or null if it doesn't say. */
    private fun likedIn(c: MediaController): Boolean? {
        val rating = userRating(c)
        val style = rating?.ratingStyle ?: -1
        return when {
            rating != null && style == Rating.RATING_HEART -> rating.hasHeart()
            rating != null && style == Rating.RATING_THUMB_UP_DOWN -> rating.isThumbUp
            else -> likeButton(c)?.let { likeLabelState(it.label) }
        }
    }

    /** What each app last said about each song being liked (the record is only written on changes). */
    private val likedSeen = HashMap<String, Boolean>()

    /** Note whether [track], playing (or paused) in [c], is liked there, for the watch's Liked covers. */
    private fun noteLiked(c: MediaController, track: Track) {
        val liked = runCatching { likedIn(c) }.getOrNull() ?: return
        val key = c.packageName + "|" + track.songKey
        if (likedSeen[key] == liked) return
        likedSeen[key] = liked
        LikedStore.set(app, track, liked)
    }

    /** The recently played songs from [pkg], newest first. */
    fun recentFrom(pkg: String): List<Track> = history.asReversed().filter { it.source == pkg }

    /** The app playing (or paused) now, else the one that played last. */
    fun lastAppPackage(ctx: Context): String? =
        active?.packageName?.takeIf { it !in excluded }
            ?: history.lastOrNull()?.source?.takeIf { it.isNotBlank() }
            ?: Prefs.lastPlayer(ctx)

    /**
     * Pass a like and/or star change to the player, if it's playing [track] and supports it.
     * Only ever likes (never un-likes) through buttons.
     */
    fun applyRating(track: Track, liked: Boolean?, stars: Int?) {
        val c = pickActive() ?: return
        val playing = c.metadata?.let { trackFromMetadata(it) }
        if (playing == null || !playing.sameSongAs(track)) return
        val controls = c.transportControls
        val type = c.ratingType
        try {
            if (liked != null) {
                when (type) {
                    Rating.RATING_HEART -> controls.setRating(Rating.newHeartRating(liked))
                    // Thumbs: like = thumbs up, unlike = thumbs down (a dislike).
                    Rating.RATING_THUMB_UP_DOWN -> controls.setRating(Rating.newThumbRating(liked))
                    else -> if (liked) likeWithButton(c, track) else dislikeOrUnlike(c, track)
                }
            }
            if (stars != null && type == Rating.RATING_5_STARS) {
                controls.setRating(
                    if (stars == 0) Rating.newUnratedRating(Rating.RATING_5_STARS)
                    else Rating.newStarRating(Rating.RATING_5_STARS, stars.toFloat()),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Player rejected the rating", e)
        }
        changed()
    }

    private fun likeWithButton(c: MediaController, track: Track) {
        val button = likeButton(c) ?: run {
            Log.i(TAG, "${c.packageName} offers no like button: ${buttons(c).joinToString { it.label }}")
            return
        }
        when (likeLabelState(button.label)) {
            false -> press(c, button) // label says "Love"/"Like": not liked yet
            true -> Unit // already liked
            // Label doesn't say. Press once per song, and only for a button the user picked.
            null -> if (Prefs.likeButton(app, c.packageName) == button.key && Prefs.markPressedLike(app, track.songKey)) {
                press(c, button)
            }
        }
    }

    /**
     * Under 3 stars: press the player's thumbs-down if it has one (that also removes a like),
     * otherwise put the song back to not liked: a full favourite star or heart becomes empty.
     * Buttons toggle, so each is only pressed when its label says it's in the other state (or we
     * know we liked it ourselves).
     */
    private fun dislikeOrUnlike(c: MediaController, track: Track) {
        val all = buttons(c)
        val dislike = all.firstOrNull { isDislikeLabel(it.label) || isDislikeLabel(it.key) }
        if (dislike != null) {
            when (likeLabelState(dislike.label)) {
                false -> {
                    press(c, dislike)
                    Prefs.unmarkPressedLike(app, track.songKey)
                    return
                }
                true -> return // already disliked
                null -> Unit // can't tell: fall back to un-liking
            }
        }
        val like = likeButton(c, all) ?: return
        when (likeLabelState(like.label)) {
            true -> press(c, like)
            false -> Unit // not liked: nothing to remove
            null -> if (Prefs.unmarkPressedLike(app, track.songKey)) press(c, like) // we liked it, so undo
        }
    }

    /** "Try" in the phone app: press one of the playing app's buttons right now. */
    fun pressNow(pkg: String, key: String): Boolean {
        if (!started) return false
        val c = pickActive() ?: return false
        if (c.packageName != pkg) return false
        val button = buttons(c).firstOrNull { it.key == key } ?: return false
        return press(c, button).also { changed() }
    }

    private fun adjustVolume(c: MediaController, delta: Int) {
        if (delta == 0) return
        val info = c.playbackInfo
        if (info != null && info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            c.setVolumeTo((info.currentVolume + delta).coerceIn(0, info.maxVolume), 0)
            return
        }
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val direction = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(abs(delta)) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
    }
}
