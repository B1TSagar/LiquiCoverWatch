package com.coverflow.wear.face

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * Asks the watch faces to fetch one of this app's complications again (the album art, its colour,
 * the song's progress).
 *
 * The complications library this app uses (1.3.0) hands the new data straight to the watch's
 * complication system on Wear OS 6, through the Wear SDK. The way before took a broadcast to the
 * Wear OS app, which passed it on to the system's complication service, which then bound to this
 * app's complication service to ask for the data: on a Galaxy Watch4 that cost the watch about
 * 150 ms of work for every update, and several times that while it was short of memory
 * (measured), and a new song is three updates (its cover, its colour and its progress).
 *
 * Should the new way fail to start (an older watch takes the old way by itself), it falls back
 * to the old broadcast, so the faces still update.
 *
 * On the beat. Each ask waits for one of the Expedition face's seconds hand's beats ([Beat]): it
 * goes at the moment that has the face fetch it [AFTER_BEAT_MS] after the beat, by how long asks
 * have lately taken to reach this app's complication services ([answered]). Everything on that
 * face changes as the seconds hand beats, so an update that came between beats would have the
 * watch draw a frame of its own for it. Asks made while one waits go with it.
 *
 * Rolling load. Each beat takes only so much of this work ([costOf]: the cover is a new picture
 * for the face to unpack and draw, 3; its colour has the face draw its hands, compass and glass
 * again, 3; the progress line, 1), and what doesn't fit rolls on to the next beat, and the next: a
 * budget that fills up again by so much each beat, with so much at most ready at once (a bucket,
 * [refill] and [capacity]). How much depends on how busy the watch is ([load]): how late the
 * beats' asks get to go (this app's main thread waits for the watch's two cores, which the face
 * keeps busy), and how much longer than usual asks take to reach the complication services. So
 * a new song's cover goes up in one beat and its colour in the next (calm), or a beat or two
 * later still (busy), rather than all of it landing in one frame. The face companion hears when
 * this app's work for a new song will be done ([busyUntil], with the song: see NowPlayingLink),
 * and sends the song's name and round cover after it. Nothing waits more than [MAX_WAIT_BEATS].
 *
 * After a song starts. When the music starts playing (from a pause, or from nothing), the watch's
 * own Now bar slides in at the bottom of the face, and that's when the watch is busiest: so the
 * colour, the heaviest change (the face draws its hands, compass and glass again in it), waits
 * until the watch has settled ([songStarted]): until this app's main thread, checked every
 * [PROBE_MS], has run on time [CALM_PROBES] times running, after [SETTLE_MIN_MS] at the least and
 * [SETTLE_MAX_MS] at the most. The cover and the progress line go at once, as before. (Skipping
 * from song to song while the music plays doesn't wait: the Now bar's already up.)
 *
 * Kept awake. A watch freezes an app it isn't showing soon after it's done, and an ask still
 * waiting for its beat would then wait until the app next woke. So whatever woke this app (the
 * phone's message, a broadcast) holds it until the asks have gone ([idle]: see FaceSyncService).
 */
object FaceUpdates {
    private const val TAG = "FaceUpdates"

    // The old way's broadcast (as the library sent it before Wear OS 6).
    private const val RECEIVER_PACKAGE = "com.google.android.wearable.app"
    private const val ACTION_REQUEST_UPDATE = "android.support.wearable.complications.ACTION_REQUEST_UPDATE"
    private const val ACTION_REQUEST_UPDATE_ALL = "android.support.wearable.complications.ACTION_REQUEST_UPDATE_ALL"
    private const val EXTRA_PROVIDER_COMPONENT = "android.support.wearable.complications.EXTRA_PROVIDER_COMPONENT"
    private const val EXTRA_COMPLICATION_IDS = "android.support.wearable.complications.EXTRA_COMPLICATION_IDS"
    private const val EXTRA_PENDING_INTENT = "android.support.wearable.complications.EXTRA_PENDING_INTENT"

    /** An ask is timed so the complication service is asked for the data this long after a beat (ms). */
    const val AFTER_BEAT_MS = 20L

    /** How long asks are taken to take before any has been timed (ms): a few tens on Wear OS 6. */
    private const val TRIP_START_MS = 40f

    /** How much work a fetch of [component]'s complication is for the face (see the class's comment). */
    private fun costOf(component: ComponentName): Float = when (component.className) {
        CoverArtComplicationService::class.java.name, AlbumColourComplicationService::class.java.name -> 3f
        else -> 1f
    }

    /**
     * Which goes first when a beat can't take them all: the cover (what shows most), then its
     * colour, then the progress line.
     */
    private fun rankOf(component: ComponentName): Int = when (component.className) {
        CoverArtComplicationService::class.java.name -> 0
        AlbumColourComplicationService::class.java.name -> 1
        else -> 2
    }

    /** Whether [component]'s ask waits for the watch to settle after a song starts (the colour). */
    private fun waitsToSettle(component: ComponentName): Boolean =
        component.className == AlbumColourComplicationService::class.java.name

    /** At least this much work makes a beat a busy one, for the companion (see [busyUntil]). */
    private const val HEAVY = 2f

    /** However much there is to do, an ask goes once it has waited this many beats (1 s). */
    private const val MAX_WAIT_BEATS = 8L

    // ---- How busy the watch is -------------------------------------------------------------------

    /**
     * How busy the watch is: 0 when it keeps up with ease, 1 when the beats' asks go [LATE_UNIT_MS]
     * late or asks take [TRIP_UNIT_MS] longer than usual to arrive, and so on, smoothed (it rises
     * quickly and falls slowly, so a burst of work doesn't let the next straight in).
     */
    @Volatile
    private var load = 0f
    private const val LATE_UNIT_MS = 30f
    private const val TRIP_UNIT_MS = 60f

    /** How busy the watch is now: 0 calm, 1 busy, 2 very busy. */
    fun level(): Int = when {
        load < 0.6f -> 0
        load < 1.5f -> 1
        else -> 2
    }

    // Each beat's budget, by level: what it fills up by each beat, and what it holds at most.
    // Calm, a beat takes up to four (a cover and its progress line, but not a cover and its
    // colour); very busy, about one a beat.
    private val REFILL = floatArrayOf(3f, 2f, 1f)
    private val CAPACITY = floatArrayOf(4f, 3f, 3f)
    private fun refill() = REFILL[level()]
    private fun capacity() = CAPACITY[level()]

    private fun noteLoad(sample: Float) {
        val s = sample.coerceIn(0f, 4f)
        load += (s - load) * (if (s > load) 0.5f else 0.15f)
    }

    // ---- After a song starts ---------------------------------------------------------------------

    /** How often the main thread is checked while the watch settles after a song starts (ms). */
    private const val PROBE_MS = 40L

    /** A check that runs no later than this (ms) finds the watch calm. */
    private const val CALM_LATE_MS = 8L

    /** ... and this many calm checks running means it has settled. */
    private const val CALM_PROBES = 3

    /** The colour waits at least this long after a song starts (ms), and at most this long. */
    private const val SETTLE_MIN_MS = 450L
    private const val SETTLE_MAX_MS = 1_400L

    @Volatile
    private var settling = false
    private var settleStart = 0L
    private var probeDue = 0L
    private var calm = 0

    private val probe = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val late = now - probeDue
            calm = if (late <= CALM_LATE_MS) calm + 1 else 0
            noteLoad(late / LATE_UNIT_MS)
            val waited = now - settleStart
            if ((waited >= SETTLE_MIN_MS && calm >= CALM_PROBES) || waited >= SETTLE_MAX_MS) {
                settling = false
                if (Trace.isEnabled()) Trace.setCounter("face settling", 0L)
                Log.i(TAG, "The watch settled ${waited} ms after the song started")
                synchronized(pending) { schedule() }
                // (The companion hears when the colour will have gone: it sends the date pill's
                // song after it.)
                app?.let { NowPlayingLink.update(it, force = true) }
            } else {
                probeDue = now + PROBE_MS
                main.postDelayed(this, PROBE_MS)
            }
        }
    }

    /**
     * The music has just started playing (from a pause or from nothing): the colour waits until the
     * watch has settled (see the class's comment). Call before asking for the colour.
     */
    fun songStarted(context: Context) {
        app = context.applicationContext
        settleStart = SystemClock.uptimeMillis()
        settling = true
        if (Trace.isEnabled()) Trace.setCounter("face settling", 1L)
        main.post {
            settleStart = SystemClock.uptimeMillis()
            calm = 0
            probeDue = settleStart + PROBE_MS
            main.removeCallbacks(probe)
            main.postDelayed(probe, PROBE_MS)
        }
    }

    // ---- The asks waiting ------------------------------------------------------------------------

    /** One complication's ask: its slots (none: every face's), its cost and rank, and from which beat. */
    private class Ask(var ids: IntArray, val cost: Float, val rank: Int, val since: Long, val settles: Boolean)

    /** One requester for each complication, made once (the library means them to be kept). */
    private val requesters = java.util.concurrent.ConcurrentHashMap<ComponentName, ComplicationDataSourceUpdateRequester>()

    private val main = Handler(Looper.getMainLooper())

    private val pending = LinkedHashMap<ComponentName, Ask>()
    private var waiting = false
    private var plannedBeat = 0L
    private var plannedAt = 0L
    private var tokens = CAPACITY[0]
    private var tokensBeat = Long.MIN_VALUE
    @Volatile
    private var app: Context? = null

    // When each complication was last asked for (elapsed ms), and how long asks have been taking
    // to reach the complication services (ms, smoothed: half way to the latest each time), and
    // about the least they take (it creeps up slowly between, so it follows the watch).
    private val askedAt = java.util.concurrent.ConcurrentHashMap<ComponentName, Long>()
    @Volatile
    private var tripMs = TRIP_START_MS
    @Volatile
    private var tripFloor = TRIP_START_MS
    private const val FLOOR_CREEP_MS = 0.1f

    /** An ask taking longer than this to arrive (ms) counts toward how busy the watch is, whatever the usual. */
    private const val TRIP_SLOW_MS = 120f

    private val sendWaiting = Runnable {
        // (How late this ran: the watch's cores were busy.)
        noteLoad((SystemClock.uptimeMillis() - plannedAt) / LATE_UNIT_MS)
        val asks = synchronized(pending) {
            waiting = false
            pick(plannedBeat)
        }
        val context = app
        if (context != null) for ((component, ids) in asks) send(context, component, ids)
        synchronized(pending) { schedule() }
    }

    /**
     * Every face showing [service]'s complication fetches it again (only those [ids], if given),
     * just after one of the seconds hand's next beats: the next, unless that has its fill of work.
     */
    fun request(context: Context, service: Class<*>, vararg ids: Int) {
        val ctx = context.applicationContext
        app = ctx
        val component = ComponentName(ctx, service)
        synchronized(pending) {
            val had = pending[component]
            if (had == null) {
                pending[component] = Ask(ids.copyOf(), costOf(component), rankOf(component), Beat.at(System.currentTimeMillis()), waitsToSettle(component))
            } else {
                had.ids = if (had.ids.isEmpty() || ids.isEmpty()) IntArray(0) else (had.ids.toSet() + ids.toSet()).toIntArray()
            }
            schedule()
        }
    }

    /**
     * Until when (wall clock, ms) the watch may still be settling after a song started (at the
     * latest: [SETTLE_MAX_MS]); 0 once it has. The companion holds back its own work meanwhile.
     */
    fun settlingUntil(): Long =
        if (!settling) 0L else System.currentTimeMillis() + (settleStart + SETTLE_MAX_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    /** Whether every ask has gone (nothing waits for a beat, or for the watch to settle). */
    fun idle(): Boolean = synchronized(pending) { pending.isEmpty() && !waiting } && !settling

    /** Whether [a] waits now: for the watch to settle after a song started. */
    private fun held(a: Ask): Boolean = settling && a.settles

    /** Plans the next beat's asks (under the lock): just after the first beat an ask can reach. */
    private fun schedule() {
        if (waiting || pending.isEmpty()) return
        // (Only asks held until the watch settles: the settling's end plans them.)
        if (pending.values.all { held(it) }) return
        val now = System.currentTimeMillis()
        val trip = tripMs.toLong()
        val beat = Beat.at(now + trip - AFTER_BEAT_MS - 1) + 1
        val delay = (Beat.time(beat) + AFTER_BEAT_MS - trip - now).coerceAtLeast(0L)
        plannedBeat = beat
        plannedAt = SystemClock.uptimeMillis() + delay
        waiting = true
        main.postDelayed(sendWaiting, delay)
    }

    /**
     * The asks that go at [beat] (under the lock), taken off the waiting list: in rank order, then
     * oldest first, as many as the beat's budget takes (passing over one that doesn't fit for one
     * that does), and one that's waited [MAX_WAIT_BEATS] whatever; none held to settle.
     */
    private fun pick(beat: Long): List<Pair<ComponentName, IntArray>> {
        val cap = capacity()
        tokens = if (tokensBeat == Long.MIN_VALUE) cap else minOf(cap, tokens + refill() * (beat - tokensBeat).coerceAtLeast(0L))
        tokensBeat = beat
        val out = ArrayList<Pair<ComponentName, IntArray>>()
        for ((component, a) in pending.entries.sortedWith(compareBy({ it.value.rank }, { it.value.since }))) {
            if (held(a)) continue
            val fits = a.cost <= tokens + 0.001f || (out.isEmpty() && tokens >= cap - 0.001f)
            if (!fits && beat - a.since < MAX_WAIT_BEATS) continue
            tokens -= a.cost
            out.add(component to a.ids)
        }
        for ((component, _) in out) pending.remove(component)
        if (Trace.isEnabled()) {
            Trace.setCounter("face load x100", (load * 100f).toLong())
            Trace.setCounter("face budget x10", (tokens * 10f).toLong())
            Trace.setCounter("face asks waiting", pending.size.toLong())
        }
        return out
    }

    /**
     * Until when (wall clock, ms) the asks waiting now keep the face busy: the beat the last one
     * with [HEAVY] work in it will go at, as the budget stands (0 if none waits). Worked out as
     * [pick] will go through them, beat by beat. While the watch settles after a song started, the
     * colour goes no sooner than it may have settled by: worked out as if at the latest
     * ([SETTLE_MAX_MS]), and told again once it has (see [probe]).
     */
    fun busyUntil(): Long = synchronized(pending) {
        if (pending.isEmpty()) return 0L
        val now = System.currentTimeMillis()
        var beat = if (waiting) plannedBeat else Beat.at(now + tripMs.toLong() - AFTER_BEAT_MS - 1) + 1
        val settledBeat = if (settling) {
            Beat.at(now + (settleStart + SETTLE_MAX_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)) + 1
        } else Long.MIN_VALUE
        val cap = capacity()
        var t = if (tokensBeat == Long.MIN_VALUE) cap else minOf(cap, tokens + refill() * (beat - tokensBeat).coerceAtLeast(0L))
        val left = pending.values.sortedWith(compareBy({ it.rank }, { it.since })).toMutableList()
        var last = 0L
        var guard = 0
        while (left.isNotEmpty() && guard++ < 48) {
            var first = true
            val iter = left.iterator()
            while (iter.hasNext()) {
                val a = iter.next()
                if (a.settles && beat < settledBeat) continue
                val fits = a.cost <= t + 0.001f || (first && t >= cap - 0.001f)
                if (!fits && beat - a.since < MAX_WAIT_BEATS) continue
                t -= a.cost
                first = false
                if (a.cost >= HEAVY) last = beat
                iter.remove()
            }
            beat++
            t = minOf(cap, t + refill())
        }
        if (last == 0L) 0L else Beat.time(last)
    }

    /**
     * A face asked [service]'s complication for its data (from its onComplicationRequest): how long
     * that took from the ask is noted, asks are timed by it, and how much longer than usual it
     * took is part of how busy the watch is.
     */
    fun answered(context: Context, service: Class<*>) {
        val at = askedAt.remove(ComponentName(context.applicationContext, service)) ?: return
        val trip = (SystemClock.elapsedRealtime() - at).toFloat()
        if (trip < 0f || trip > 3_000f) return
        tripMs += (trip - tripMs) * 0.5f
        // (The usual: about the least they take, followed down a third of the way at a time, so
        // one quick one doesn't make the rest look slow, and up by a hair at a time.)
        tripFloor = if (trip < tripFloor) tripFloor + (trip - tripFloor) * 0.3f else tripFloor + FLOOR_CREEP_MS
        // (Slow next to the usual, or slow at all: a watch busy for long makes the usual slow too.)
        noteLoad(maxOf(trip - tripFloor, trip - TRIP_SLOW_MS) / TRIP_UNIT_MS)
    }

    private fun send(app: Context, component: ComponentName, ids: IntArray) {
        val name = component.shortClassName.substringAfterLast('.')
        Log.i(TAG, "Asking the faces for $name")
        // (Marked in a system trace of the watch that traces this app, as LiquiWatch's trace
        // recorder does: each ask shows, by complication, next to what the watch does with it.)
        val traced = Trace.isEnabled()
        if (traced) Trace.beginSection("ask faces: $name")
        askedAt[component] = SystemClock.elapsedRealtime()
        try {
            val requester = requesters.getOrPut(component) { ComplicationDataSourceUpdateRequester.create(app, component) }
            if (ids.isEmpty()) requester.requestUpdateAll() else requester.requestUpdate(*ids)
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't ask for $name the new way; asking the old way", t)
            broadcast(app, component, ids)
        } finally {
            if (traced) Trace.endSection()
        }
    }

    private fun broadcast(context: Context, component: ComponentName, ids: IntArray) {
        try {
            val intent = Intent(if (ids.isEmpty()) ACTION_REQUEST_UPDATE_ALL else ACTION_REQUEST_UPDATE)
                .setPackage(RECEIVER_PACKAGE)
                .putExtra(EXTRA_PROVIDER_COMPONENT, component)
                // (The system checks the request comes from the complication's own app by this.)
                .putExtra(EXTRA_PENDING_INTENT, PendingIntent.getActivity(context, 0, Intent(""), PendingIntent.FLAG_IMMUTABLE))
            if (ids.isNotEmpty()) intent.putExtra(EXTRA_COMPLICATION_IDS, ids)
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't ask the faces for ${component.shortClassName}", t)
        }
    }
}
