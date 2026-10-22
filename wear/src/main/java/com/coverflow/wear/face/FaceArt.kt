package com.coverflow.wear.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.coverflow.shared.CoverPacket
import com.coverflow.shared.FaceArtPacket
import com.coverflow.shared.Paths
import com.coverflow.shared.PlayerState
import com.coverflow.wear.PhoneLink
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The album art behind the LiquiWatch Expedition watch face.
 *
 * While a song plays, the face's background becomes its cover, blurred and darkened as behind
 * every screen of the app; when it pauses or stops, the face goes back to its own background.
 * The watch face can't talk to the phone, so the watch app does it for the face:
 *
 *  - [FaceSyncService] wakes up whenever the phone's player state changes (play, pause, a new
 *    song), even with Cover Flow closed, and notes it here;
 *  - [CoverArtComplicationService] hands the face the blurred cover (a "photo" complication)
 *    while something plays, and nothing otherwise, which leaves the face's own background;
 *  - [AlbumColourComplicationService] hands it the cover's colour, toned as Now Playing's
 *    sliders, for its hands and compass knob;
 *  - [SongProgressComplicationService] hands it where the song is, in that colour, for the
 *    thin progress line round its compass glass; nothing while the music is paused.
 *
 * The phone does all the work on the pictures: it blurs and sizes each song's background
 * ([com.coverflow.shared.FaceBackdrop]) and works out its colour, sends the next song's a few
 * seconds into the song before it, and each song's as it starts (or when the watch asks). The
 * watch only keeps them, decoded once as they arrive, and hands them on ([gotFaceArt],
 * [gotNextFaceArt]): it never blurs, resizes or compresses a picture itself. The next song's is
 * kept aside, so the face switches to it the moment it starts; and the last few songs' are kept
 * too ([RECENT]), so a song played again goes up at once, from what's here, and the phone needn't
 * send it again. A song whose background isn't here yet keeps the face as it is until it comes.
 * Each comes with the cover round, for the face's date pill (by way of the face companion: see
 * [NowPlayingLink]), which goes up with the song the same way. A pause takes the face back to its own
 * background once it has lasted [PAUSE_GRACE_MS]: skipping songs doesn't flash it in between,
 * whether the phone reports a skip as between songs ([PlayerState.between]) or, briefly, paused.
 *
 * The phone tells the watch twice: in a small message the moment the state changes
 * ([Paths.FACE_STATE]), and in the synced state item, a little later. Each is stamped with when
 * the phone made it, so whichever comes second, if older, is left alone.
 */
object FaceArt {
    private const val TAG = "CoverFlowFace"
    private const val PREFS = "face_art"
    private const val KEY_ART = "art"
    private const val KEY_PLAYING = "playing"
    private const val KEY_STOPPED_AT = "stopped_at"
    private const val KEY_KNOWN = "known"
    private const val KEY_SLOTS = "slots"
    private const val KEY_BACKGROUND_OF = "background_of"
    private const val KEY_BACKGROUND_SUM = "background_sum"
    private const val KEY_TONE = "tone"
    private const val KEY_TONE_OF = "tone_of"
    private const val KEY_NEXT_OF = "next_of"
    private const val KEY_NEXT_TONE = "next_tone"
    private const val KEY_NEXT_SUM = "next_sum"
    private const val KEY_SONG_START = "song_start"
    private const val KEY_SONG_END = "song_end"
    private const val KEY_FACE_ASKED = "face_asked"
    private const val KEY_FACE_ASKED_AT = "face_asked_at"
    private const val KEY_HELLO_AT = "phone_hello_at"
    private const val KEY_STATE_SEQ = "state_seq"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_FACE_SEEN = "face_seen"

    /** The background the face shows (the phone's JPEG). */
    private const val BACKGROUND_FILE = "face_background"
    private const val OLD_BACKGROUND_FILE = "face_background.png"

    /** The next song's background, sent ahead (the phone's JPEG). */
    private const val NEXT_FILE = "face_background_next"

    /**
     * How long a pause must last before the face goes back to its own background (ms): long
     * enough that a player that reports "paused" as it skips to the next song doesn't blink it
     * (most report "skipping" or "loading" instead: see [PlayerState.between]). On the watch, every
     * skip came as "paused" 0.24 to 1.3 s before the next song, so at a quarter of a second the face
     * went back to its own background and colour and then to the new cover: twice the work, and
     * later. It used to be five seconds.
     */
    const val PAUSE_GRACE_MS = 1_500L

    /** How long a pause must last before the progress line goes (ms): as the background. */
    private const val PROGRESS_SETTLE_MS = PAUSE_GRACE_MS

    /**
     * A state this much older (ms) than the last one taken is from after the phone restarted (its
     * clock starts again): taken. (A synced item arrives a few seconds late at the most.)
     */
    private const val RESTART_MS = 60_000L

    /** A song's start moving by less than this (ms) isn't a seek, just the phone's clock. */
    private const val SEEK_MS = 1_000L

    /** Don't ask the phone for the same song's face art more than once a minute. */
    private const val ASK_GAP_MS = 60_000L

    /**
     * The phone sends a watch the songs' backgrounds by itself only while the watch has asked for
     * one lately, so with them all coming by themselves it's still asked this often (ms).
     */
    private const val HELLO_MS = 24 * 3_600_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var settle: Runnable? = null
    @Volatile
    private var progressSettle: Runnable? = null

    /**
     * Whether nothing here waits to go to the faces or the companion (a pause's settle, an ask for
     * the next beat): whatever woke the app holds it until then ([holdUntilDone]), as the watch
     * freezes an app it isn't showing soon after it's done.
     */
    fun done(): Boolean = settle == null && progressSettle == null && FaceUpdates.idle()

    /** Waits (off the main thread) until [done], for [maxMs] at the most. */
    fun holdUntilDone(maxMs: Long = HOLD_MAX_MS) {
        val until = SystemClock.uptimeMillis() + maxMs
        while (!done() && SystemClock.uptimeMillis() < until) {
            try {
                Thread.sleep(20L)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** For a broadcast's receiver: finishes [result] once [done] (on the main thread), [maxMs] at the most. */
    fun finishWhenDone(result: android.content.BroadcastReceiver.PendingResult, maxMs: Long = HOLD_MAX_MS) {
        val until = SystemClock.uptimeMillis() + maxMs
        val check = object : Runnable {
            override fun run() {
                if (done() || SystemClock.uptimeMillis() >= until) {
                    try {
                        result.finish()
                    } catch (e: Exception) {
                        // (Finished already.)
                    }
                } else {
                    main.postDelayed(this, 20L)
                }
            }
        }
        main.post(check)
    }

    /** The longest an app is held for its asks (ms): the most a settle and the asks' beats take. */
    private const val HOLD_MAX_MS = 2_500L

    // ---- Off the main thread ------------------------------------------------------------------

    /**
     * Where the face's background is read (decoded from its file, after the app was restarted)
     * for the complications: two threads, at the usual priority. (They were at background
     * priority, to leave the watch's two cores to the face as it drew the new song; but a thread at
     * background priority is all but stopped while the face keeps both cores busy, and the album
     * art then waited until Cover Flow was opened.)
     */
    val decoder = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "CoverFlowFace").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** What's playing, as last heard from the phone. */
    data class Now(val artKey: String?, val playing: Boolean, val stoppedAt: Long, val known: Boolean)

    /**
     * The playing song's time: when it started and when it ends (wall-clock ms, at its playing
     * speed), as if it had played straight through. Only while it plays.
     */
    data class Song(val startedAt: Long, val endsAt: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun now(ctx: Context): Now {
        val p = prefs(ctx)
        return Now(
            artKey = p.getString(KEY_ART, null),
            playing = p.getBoolean(KEY_PLAYING, false),
            stoppedAt = p.getLong(KEY_STOPPED_AT, 0L),
            known = p.getBoolean(KEY_KNOWN, false),
        )
    }

    /** The song last heard of (playing or not): its name and artist ("" if none). */
    fun track(ctx: Context): Pair<String, String> {
        val p = prefs(ctx)
        return (p.getString(KEY_TITLE, null) ?: "") to (p.getString(KEY_ARTIST, null) ?: "")
    }

    /** The playing song's time, or null while nothing plays (or its length isn't known). */
    fun song(ctx: Context): Song? {
        val p = prefs(ctx)
        val start = p.getLong(KEY_SONG_START, 0L)
        val end = p.getLong(KEY_SONG_END, 0L)
        return if (end > start && start > 0L) Song(start, end) else null
    }

    private fun songOf(state: PlayerState): Song? {
        val duration = state.current?.durationMs ?: 0L
        if (!state.isPlaying || duration <= 0L || state.capturedAt <= 0L) return null
        val speed = if (state.speed > 0f) state.speed else 1f
        val startedAt = state.capturedAt - (state.positionMs / speed).toLong()
        return Song(startedAt, startedAt + (duration / speed).toLong())
    }

    /** Whether the face should show the cover: while playing, and for a moment after a pause. */
    fun showsArt(now: Now, nowMs: Long = System.currentTimeMillis()): Boolean =
        now.artKey != null && (now.playing || nowMs - now.stoppedAt < PAUSE_GRACE_MS)

    // ---- Which faces show it ------------------------------------------------------------------

    /**
     * A face started (or stopped) showing one of the complications in one of its slots (or asked
     * one for its data: [showing], and that a face uses them is noted, see [inUse]).
     */
    fun setSlot(ctx: Context, id: Int, showing: Boolean) {
        val p = prefs(ctx)
        val slots = p.getStringSet(KEY_SLOTS, emptySet()).orEmpty().toMutableSet()
        val changed = if (showing) slots.add(id.toString()) else slots.remove(id.toString())
        if (changed) p.edit().putStringSet(KEY_SLOTS, slots).apply()
        if (showing) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - p.getLong(KEY_FACE_SEEN, 0L) > FACE_SEEN_NOTE_MS) p.edit().putLong(KEY_FACE_SEEN, nowMs).apply()
        }
    }

    /**
     * Whether a watch face shows them (otherwise nothing is fetched for them): one of their slots
     * is noted as showing them, or a face asked one of them for its data within [FACE_SEEN_MS].
     * (The slots alone could all be gone while a face still shows them: when the face is put on
     * again or updated, the watch can tell the old one's slots have gone after it has told the new
     * one's have come, and they're the same slots. Then nothing here asked the faces for anything,
     * so no face asked for anything either, and the album art never came back.)
     */
    fun inUse(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.getStringSet(KEY_SLOTS, emptySet()).orEmpty().isNotEmpty()) return true
        return System.currentTimeMillis() - p.getLong(KEY_FACE_SEEN, 0L) < FACE_SEEN_MS
    }

    /** A face that asked for the album art within this long (ms) still shows it, as far as this app goes. */
    private const val FACE_SEEN_MS = 7 * 86_400_000L

    /** (When a face last asked is noted at most this often: ms.) */
    private const val FACE_SEEN_NOTE_MS = 3_600_000L

    // ---- What the phone says ------------------------------------------------------------------

    /**
     * A new player state from the phone (its message, or its synced item): false if it was left
     * alone, as older than one already taken.
     */
    fun noteState(ctx: Context, state: PlayerState): Boolean = synchronized(this) { takeState(ctx, state) }

    private fun takeState(ctx: Context, state: PlayerState): Boolean {
        val app = ctx.applicationContext
        // Older than the last taken (the synced item, after the message of a newer one): left
        // alone. (The phone stamps each with a clock that only goes forward until it restarts.)
        val lastSeq = prefs(app).getLong(KEY_STATE_SEQ, 0L)
        if (state.seq in 1 until lastSeq && lastSeq - state.seq < RESTART_MS) return false
        if (state.seq > 0L) prefs(app).edit().putLong(KEY_STATE_SEQ, state.seq).apply()
        val key = state.current?.artKey?.takeIf { it.isNotBlank() }
        // (Between songs counts as playing: a skip doesn't take the album art down.)
        val playing = state.inPlay && key != null
        val before = now(app)
        val nowMs = System.currentTimeMillis()
        val stoppedAt = when {
            playing -> 0L
            before.playing || before.stoppedAt == 0L -> nowMs
            else -> before.stoppedAt
        }
        val songBefore = song(app)
        // (Loading or skipping to a song: the line carries on as it was until the music plays,
        // then the new song's takes over; it doesn't go and come back.)
        val songNow = if (state.between) songBefore else songOf(state)
        val newSong = playing && (!before.playing || before.artKey != key)
        prefs(app).edit()
            .putString(KEY_ART, key ?: before.artKey)
            .putBoolean(KEY_PLAYING, playing)
            .putLong(KEY_STOPPED_AT, stoppedAt)
            .putBoolean(KEY_KNOWN, true)
            .putLong(KEY_SONG_START, songNow?.startedAt ?: 0L)
            .putLong(KEY_SONG_END, songNow?.endsAt ?: 0L)
            .apply()
        // (The song's name and artist, for the companion's date pill: kept while it's known. A
        // state without them (from an older phone app's face message) leaves them be: a blank name
        // took the pill back to the date until the synced state brought it.)
        state.current?.takeIf { it.title.isNotBlank() }?.let { t ->
            prefs(app).edit().putString(KEY_TITLE, t.title).putString(KEY_ARTIST, t.artist).apply()
        }
        // (A song starting again may ask the phone for its background again at once.)
        if (newSong) prefs(app).edit().remove(KEY_FACE_ASKED).apply()
        // Whether a face shows the album art: if not, only the companion hears of the song (for
        // the Expedition face's date pill, which it carries itself), nothing's fetched or asked.
        val used = inUse(app)
        // (The music starting from a pause, or from nothing: the watch's Now bar slides in about
        // now, so the colour waits for the watch to settle. See FaceUpdates.songStarted. A
        // "pause" shorter than the grace, as a skip is, never showed: the album art stayed up.)
        val wasShowing = before.playing || (before.stoppedAt > 0L && nowMs - before.stoppedAt < PAUSE_GRACE_MS)
        val started = playing && !wasShowing

        // A new song's background goes up with it if it's here: the phone sends the next song's
        // ahead (kept aside, see gotNextFaceArt), and each song's as it starts. If it isn't here
        // yet, the face stays as it is until it comes (gotFaceArt).
        val toneBefore = shownTone(app)
        val artNow = used && newSong && key != null && (
            hasFaceArt(app, key) || takeNext(app, key) ||
                (state.aheadKey.isNotEmpty() && takeNext(app, key, from = state.aheadKey)) || takeRecent(app, key)
            )
        if (newSong) Log.i(TAG, "Song $key plays: its background ${if (!used) "isn't wanted (no face shows it)" else if (artNow) "is here" else "isn't here yet"}")

        // The progress line: sent again when the music plays, skips or seeks, and taken away
        // when a pause lasts a moment. (A state that changes nothing, as the synced item after its
        // message, leaves a pending one be.) Only when it moved: skipping to a song (between
        // songs) leaves the line as it was until the music plays, so it isn't sent again with the
        // cover then, as it was (each update is work for the face as it draws the new cover).
        if (!used) {
            // (No face shows it.)
        } else if (songNow != null) {
            progressSettle?.let(main::removeCallbacks)
            progressSettle = null
            val moved = songBefore == null ||
                kotlin.math.abs(songNow.startedAt - songBefore.startedAt) > SEEK_MS ||
                kotlin.math.abs(songNow.endsAt - songBefore.endsAt) > SEEK_MS
            if (moved) SongProgressComplicationService.refresh(app)
        } else if (songBefore != null && !(before.playing && !playing)) {
            // (On a pause the background's settle, below, takes the line away with it.)
            progressSettle?.let(main::removeCallbacks)
            val r = Runnable {
                progressSettle = null
                if (song(app) == null) SongProgressComplicationService.refresh(app)
            }
            progressSettle = r
            main.postDelayed(r, PROGRESS_SETTLE_MS)
        }

        if (playing) {
            // (Playing again: a pause's settle, if one is pending, is off.)
            settle?.let(main::removeCallbacks)
            settle = null
            // (Started from a pause or from nothing: the colour, now or as the cover comes, waits
            // for the watch to settle.)
            if (used && started) FaceUpdates.songStarted(app)
            // A song started, or the next one: its cover goes up now if it's here, or as soon as
            // the phone's comes (gotFaceArt; the progress line has gone up above, if it moved).
            // The colour only if it's another: the face draws everything in it again for a new one.
            if (artNow) {
                CoverArtComplicationService.refresh(app)
                if (!wasShowing || shownTone(app) != toneBefore) AlbumColourComplicationService.refresh(app)
            }
            // The companion's date pill: the song as it plays (or skips to the next), with its
            // round cover if it's here; after the asks above, so it hears when they'll be done
            // (FaceUpdates.busyUntil). A pause once it has lasted (below, with the background).
            // Whether or not a face shows the album art: the pill is the companion's own.
            NowPlayingLink.update(app)
        } else if (before.playing) {
            // Paused or stopped: the face's own background comes back once it stays that way.
            settle?.let(main::removeCallbacks)
            val r = Runnable {
                settle = null
                if (!now(app).playing) {
                    if (inUse(app)) refreshFaces(app)
                    NowPlayingLink.update(app)
                }
            }
            settle = r
            main.postDelayed(r, PAUSE_GRACE_MS + 30L)
        }
        return true
    }

    /**
     * The app has woken (kept awake for the watch face: see FaceKeeperService), and may have been
     * frozen while the phone said something: its last state, which the Data Layer keeps on the
     * watch, is taken if it's newer than the last heard; and if a song plays, the faces are asked
     * for the album art again and the companion told, in case either missed it. In the background.
     */
    fun catchUp(ctx: Context) {
        val app = ctx.applicationContext
        work.launch {
            try {
                readKnownState(app)
                val now = now(app)
                if (showsArt(now)) {
                    if (inUse(app)) refreshFaces(app)
                    NowPlayingLink.update(app, force = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't catch up on the phone's state", e)
            }
        }
    }

    /**
     * Before the first state has arrived (the face was just put on): the phone's last state,
     * which the Data Layer keeps on the watch. Blocking; call off the main thread.
     */
    fun loadKnownState(ctx: Context) {
        if (now(ctx).known) return
        readKnownState(ctx)
    }

    /** The phone's last state, as the Data Layer keeps it on the watch (taken if it's newer). Blocking. */
    private fun readKnownState(ctx: Context) {
        try {
            val items = Tasks.await(Wearable.getDataClient(ctx).dataItems, 5, TimeUnit.SECONDS)
            try {
                for (item in items) {
                    if (item.uri.path != Paths.STATE) continue
                    val json = DataMapItem.fromDataItem(item).dataMap.getString("json") ?: continue
                    PlayerState.fromJsonString(json)?.let { noteState(ctx, it) }
                }
            } finally {
                items.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No player state yet", e)
        }
    }

    /**
     * A cover arrived from the phone: kept for Cover Flow if it's the playing song's (it keeps
     * the rest itself). The face's background isn't made from it: the phone sends that.
     */
    fun gotCover(ctx: Context, key: String, bytes: ByteArray) {
        val app = ctx.applicationContext
        if (key != now(app).artKey || !inUse(app)) return
        if (CoverPacket.fromBytes(bytes) == null) return
        writeAtomically(PhoneLink.coverFile(app, key), bytes)
    }

    /**
     * The face's background and colour for [key], made by the phone ([FaceArtPacket]): kept, and
     * the faces asked to show them if [key] plays. One for another song than the face's (it can
     * come just before its song does) is kept aside, as the next song's, and goes up as that
     * song starts ([takeNext]): the face's own is left as it is.
     */
    fun gotFaceArt(ctx: Context, key: String, bytes: ByteArray) {
        val app = ctx.applicationContext
        if (!inUse(app)) return
        if (key != now(app).artKey) {
            keepNext(app, key, bytes)
            return
        }
        val packet = FaceArtPacket.fromBytes(bytes) ?: return
        remember(app, key, packet, bytes)
        val p = prefs(app)
        val sum = packet.image.contentHashCode()
        val saved = File(app.filesDir, BACKGROUND_FILE)
        // The very one the face has (sent ahead and already up, or sent again): nothing to do.
        // (Only when it's the one decoded in this run of the app: after a restart it's taken again,
        // in case the app stopped between keeping the picture and noting whose it is.)
        if (ready?.key == key && p.getString(KEY_BACKGROUND_OF, null) == key && p.getInt(KEY_BACKGROUND_SUM, 0) == sum &&
            p.getString(KEY_TONE_OF, null) == key && p.getInt(KEY_TONE, 0) == packet.tone && saved.exists()
        ) return
        Log.i(TAG, "The face's background for $key came (${packet.image.size} bytes)")
        val sameTone = p.getString(KEY_TONE_OF, null) == key && p.contains(KEY_TONE) && p.getInt(KEY_TONE, 0) == packet.tone
        if (!writeAtomically(saved, packet.image)) return
        File(app.filesDir, OLD_BACKGROUND_FILE).delete()
        // (Decoded now, once, ready for when the face asks.)
        val decoded = decode(packet.image)
        if (decoded != null) keep(key, decoded) else if (ready?.key == key) ready = null
        decoded?.let { keepDecoded(key, it) }
        p.edit()
            .putString(KEY_BACKGROUND_OF, key)
            .putInt(KEY_BACKGROUND_SUM, sum)
            .putInt(KEY_TONE, packet.tone)
            .putString(KEY_TONE_OF, key)
            .commit()
        // (The copy kept aside for it, if it was the next song, is this one now.)
        if (p.getString(KEY_NEXT_OF, null) == key) dropNext(app)
        val now = now(app)
        if (key == now.artKey && showsArt(now)) {
            // (A sharper copy of the cover the face shows, in the same colour: only the picture
            // changes, so only the picture is sent again. A new colour has the face draw its
            // hands, compass and glass again too.)
            if (sameTone) CoverArtComplicationService.refresh(app) else refreshArt(app)
            // (The round cover for the companion's date pill, if it's another: it came with it.)
            NowPlayingLink.update(app)
        }
    }

    /**
     * The next song's background and colour ([key], [FaceArtPacket]), sent ahead by the phone:
     * kept aside, decoded, for the moment that song starts ([takeNext]). If it's already
     * playing (the song started before this came), it's that song's own.
     */
    fun gotNextFaceArt(ctx: Context, key: String, bytes: ByteArray) {
        val app = ctx.applicationContext
        if (!inUse(app)) return
        val now = now(app)
        if (key == now.artKey && now.playing) {
            gotFaceArt(app, key, bytes)
            return
        }
        keepNext(app, key, bytes)
    }

    /** Keeps [key]'s background and colour aside, decoded, as the next song's. */
    private fun keepNext(app: Context, key: String, bytes: ByteArray) {
        val packet = FaceArtPacket.fromBytes(bytes) ?: return
        remember(app, key, packet, bytes)
        val p = prefs(app)
        val sum = packet.image.contentHashCode()
        if (readyNext?.key == key && p.getString(KEY_NEXT_OF, null) == key && p.getInt(KEY_NEXT_SUM, 0) == sum &&
            File(app.filesDir, NEXT_FILE).exists()
        ) return
        Log.i(TAG, "The background for $key came ahead of its song (${packet.image.size} bytes)")
        if (!writeAtomically(File(app.filesDir, NEXT_FILE), packet.image)) return
        readyNext = decode(packet.image)?.let { keepDecoded(key, it); Ready(key, it) }
        p.edit()
            .putString(KEY_NEXT_OF, key)
            .putInt(KEY_NEXT_TONE, packet.tone)
            .putInt(KEY_NEXT_SUM, sum)
            .commit()
    }

    /**
     * If [key]'s background was sent ahead (as the next song's, under [from]: the key its queue
     * entry gave, see PlayerState.aheadKey), it's the face's now: true.
     */
    private fun takeNext(ctx: Context, key: String, from: String = key): Boolean {
        val p = prefs(ctx)
        val next = File(ctx.filesDir, NEXT_FILE)
        if (p.getString(KEY_NEXT_OF, null) != from || !next.exists()) return false
        val saved = File(ctx.filesDir, BACKGROUND_FILE)
        if (!next.renameTo(saved)) {
            val moved = try {
                saved.writeBytes(next.readBytes())
                next.delete()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't put up the next song's background", e)
                false
            }
            if (!moved) return false
        }
        File(ctx.filesDir, OLD_BACKGROUND_FILE).delete()
        p.edit()
            .putString(KEY_BACKGROUND_OF, key)
            .putInt(KEY_BACKGROUND_SUM, p.getInt(KEY_NEXT_SUM, 0))
            .putInt(KEY_TONE, p.getInt(KEY_NEXT_TONE, 0))
            .putString(KEY_TONE_OF, key)
            .remove(KEY_NEXT_OF)
            .remove(KEY_NEXT_TONE)
            .remove(KEY_NEXT_SUM)
            .commit()
        readyNext?.takeIf { it.key == from }?.let {
            ready = Ready(key, it.bitmap)
            keepDecoded(key, it.bitmap)
        }
        readyNext = null
        return true
    }

    private fun dropNext(ctx: Context) {
        File(ctx.filesDir, NEXT_FILE).delete()
        prefs(ctx).edit().remove(KEY_NEXT_OF).remove(KEY_NEXT_TONE).remove(KEY_NEXT_SUM).apply()
        readyNext = null
    }

    /**
     * The background last kept, ready to hand to the faces: so the album art complication answers
     * as quickly as the colour and the progress (no picture to read and decode first), and the
     * three reach the face together. (Never recycled: a face may still be being handed it.)
     */
    private class Ready(val key: String, val bitmap: Bitmap)

    @Volatile
    private var ready: Ready? = null

    /** The next song's, sent ahead and decoded as it came. */
    @Volatile
    private var readyNext: Ready? = null

    private fun keep(key: String, bitmap: Bitmap) {
        ready = Ready(key, bitmap)
    }

    private fun decode(image: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(image, 0, image.size)
    } catch (e: Exception) {
        null
    }

    private fun writeAtomically(file: File, bytes: ByteArray): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".face")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't keep ${file.name}", e)
        false
    }

    // ---- The songs played lately ---------------------------------------------------------------

    /**
     * How many songs' backgrounds (with their colour and round cover) the watch keeps, the latest
     * played: a song played again goes up from these at once. (The phone counts on its last six
     * being here: WatchFaceArt.kept there.) Each a few KB, in its own file, as the phone sent it.
     */
    private const val RECENT = 8
    private const val RECENT_DIR = "face_recent"
    private const val KEY_RECENT = "recent"

    /** How many of them stay decoded, ready (a background is 57 KB decoded). */
    private const val RECENT_DECODED = 3

    private val decodedRecent = object : LinkedHashMap<String, Bitmap>(RECENT_DECODED + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > RECENT_DECODED
    }

    /** The round covers of the songs kept (art key -> PNG), as read. */
    private val rounds = object : LinkedHashMap<String, ByteArray>(RECENT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > RECENT
    }

    private fun recentKeys(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_RECENT, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    /** The file [key]'s packet is kept in (named by a hash: an art key can hold any character). */
    private fun recentFile(ctx: Context, key: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return File(File(ctx.filesDir, RECENT_DIR), hash.joinToString("") { "%02x".format(it) })
    }

    /** Keeps [key]'s packet ([bytes], [packet] read) among the songs played lately. */
    private fun remember(app: Context, key: String, packet: FaceArtPacket, bytes: ByteArray) {
        synchronized(rounds) { packet.round?.let { rounds[key] = it } ?: rounds.remove(key) }
        if (!writeAtomically(recentFile(app, key), bytes)) return
        val keys = recentKeys(app).filter { it != key } + key
        for (gone in keys.dropLast(RECENT)) {
            recentFile(app, gone).delete()
            synchronized(decodedRecent) { decodedRecent.remove(gone) }
            synchronized(rounds) { rounds.remove(gone) }
        }
        prefs(app).edit().putString(KEY_RECENT, keys.takeLast(RECENT).joinToString("\n")).apply()
    }

    private fun keepDecoded(key: String, bitmap: Bitmap) {
        synchronized(decodedRecent) { decodedRecent[key] = bitmap }
    }

    private fun recentPacket(ctx: Context, key: String): FaceArtPacket? {
        if (key !in recentKeys(ctx)) return null
        return try {
            FaceArtPacket.fromBytes(recentFile(ctx, key).readBytes())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * If [key] is one of the songs played lately, its background and colour are the face's now
     * (decoded already, if it was one of the last few): true.
     */
    private fun takeRecent(ctx: Context, key: String): Boolean {
        val packet = recentPacket(ctx, key) ?: return false
        if (!writeAtomically(File(ctx.filesDir, BACKGROUND_FILE), packet.image)) return false
        File(ctx.filesDir, OLD_BACKGROUND_FILE).delete()
        val decoded = synchronized(decodedRecent) { decodedRecent[key] } ?: decode(packet.image)?.also { keepDecoded(key, it) }
        if (decoded != null) keep(key, decoded) else if (ready?.key == key) ready = null
        prefs(ctx).edit()
            .putString(KEY_BACKGROUND_OF, key)
            .putInt(KEY_BACKGROUND_SUM, packet.image.contentHashCode())
            .putInt(KEY_TONE, packet.tone)
            .putString(KEY_TONE_OF, key)
            .commit()
        Log.i(TAG, "Song $key played again: its background was kept")
        return true
    }

    /**
     * [key]'s cover, round (a PNG made on the phone at the size the face shows it), if it's here:
     * it comes with the song's background and colour, and is kept with them.
     */
    fun roundArt(ctx: Context, key: String): ByteArray? {
        synchronized(rounds) { rounds[key] }?.let { return it }
        val round = recentPacket(ctx, key)?.round ?: return null
        synchronized(rounds) { rounds[key] = round }
        return round
    }

    /** Whether the face's background and colour for [key] are on the watch. */
    fun hasFaceArt(ctx: Context, key: String): Boolean {
        val p = prefs(ctx)
        return p.getString(KEY_BACKGROUND_OF, null) == key && p.getString(KEY_TONE_OF, null) == key &&
            File(ctx.filesDir, BACKGROUND_FILE).exists()
    }

    /**
     * Asks the phone for [key]'s background and colour if they aren't here (they usually come by
     * themselves); and once a day even when they are, as the phone sends them by itself only to
     * a watch that's asked lately. Blocking; call off the main thread.
     */
    fun fetch(ctx: Context, key: String) {
        val app = ctx.applicationContext
        // (Only for the song playing now: not one the music has moved on from meanwhile.)
        val now = now(app)
        if (now.artKey != key || !now.playing) return
        val hello = System.currentTimeMillis() - prefs(app).getLong(KEY_HELLO_AT, 0L) > HELLO_MS
        if (!hasFaceArt(app, key) || hello) askFace(app, key)
    }

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [fetch], in the background (a complication answers at once with what's here meanwhile),
     * after [delayMs] (it asks the phone only for what still isn't here by then).
     */
    fun fetchLater(ctx: Context, key: String, delayMs: Long = 0L) {
        val app = ctx.applicationContext
        work.launch {
            try {
                if (delayMs > 0L) delay(delayMs)
                fetch(app, key)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't ask the phone for the face's background", e)
            }
        }
    }

    /** Asks the phone for [key]'s background and colour (at most once a minute per song). */
    private fun askFace(ctx: Context, key: String) {
        val p = prefs(ctx)
        val nowMs = System.currentTimeMillis()
        if (p.getString(KEY_FACE_ASKED, null) == key && nowMs - p.getLong(KEY_FACE_ASKED_AT, 0L) < ASK_GAP_MS) return
        p.edit().putString(KEY_FACE_ASKED, key).putLong(KEY_FACE_ASKED_AT, nowMs).apply()
        Log.i(TAG, "Asking the phone for the face's background ($key)")
        if (sendToPhone(ctx, Paths.REQ_FACE_ART, key.toByteArray(Charsets.UTF_8))) {
            p.edit().putLong(KEY_HELLO_AT, nowMs).apply()
        } else if (p.getString(KEY_FACE_ASKED, null) == key) {
            // (It didn't go: it can be asked again as soon as something next asks for it.)
            p.edit().remove(KEY_FACE_ASKED).apply()
        }
    }

    /** Sends a message to the phone; false if it couldn't. Blocking; call off the main thread. */
    private fun sendToPhone(ctx: Context, path: String, payload: ByteArray): Boolean = try {
        val info = Tasks.await(
            Wearable.getCapabilityClient(ctx).getCapability(Paths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE),
            5,
            TimeUnit.SECONDS,
        )
        val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
        if (node == null) {
            false
        } else {
            Tasks.await(Wearable.getMessageClient(ctx).sendMessage(node.id, path, payload), 5, TimeUnit.SECONDS)
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't reach the phone ($path)", e)
        false
    }

    /** Asks the faces for the album art, the album colour and the song's progress again. */
    fun refreshFaces(ctx: Context) {
        refreshArt(ctx)
        SongProgressComplicationService.refresh(ctx)
    }

    /** Asks the faces for the album art and colour again (the progress line is as it was). */
    private fun refreshArt(ctx: Context) {
        CoverArtComplicationService.refresh(ctx)
        AlbumColourComplicationService.refresh(ctx)
    }

    // ---- The album colour ---------------------------------------------------------------------

    /** The colour the faces were last handed (null if none yet). */
    private fun shownTone(ctx: Context): Int? {
        val p = prefs(ctx)
        return if (p.contains(KEY_TONE)) p.getInt(KEY_TONE, 0) else null
    }

    /**
     * The colour the face's hands and progress line take while [key] plays: Now Playing's slider
     * colour for that cover (a tone of the cover's own colour that stands out on the blurred
     * cover; see [com.coverflow.shared.FaceBackdrop.tone]), as the phone sent it with the
     * background. Until that's here, the last song's: the colour changes with the background.
     * Null if there's never been one.
     */
    fun tone(ctx: Context, @Suppress("UNUSED_PARAMETER") key: String): Int? {
        val p = prefs(ctx)
        return if (p.contains(KEY_TONE)) p.getInt(KEY_TONE, 0) else null
    }

    // ---- The background -----------------------------------------------------------------------

    /** The blurred background for [key], as the phone sent it; null while it isn't here yet. */
    fun background(ctx: Context, key: String): Bitmap? {
        val p = prefs(ctx)
        val saved = File(ctx.filesDir, BACKGROUND_FILE)
        if (p.getString(KEY_BACKGROUND_OF, null) != key || !saved.exists()) return null
        ready?.takeIf { it.key == key }?.let { return it.bitmap }
        val decoded = BitmapFactory.decodeFile(saved.path) ?: return null
        keep(key, decoded)
        return decoded
    }

    /** The last background (for the moment between a new song and its own arriving). */
    fun lastBackground(ctx: Context): Bitmap? {
        ready?.let { return it.bitmap }
        val saved = File(ctx.filesDir, BACKGROUND_FILE).takeIf { it.exists() }
            ?: File(ctx.filesDir, OLD_BACKGROUND_FILE).takeIf { it.exists() }
            ?: return null
        return BitmapFactory.decodeFile(saved.path)
    }
}
