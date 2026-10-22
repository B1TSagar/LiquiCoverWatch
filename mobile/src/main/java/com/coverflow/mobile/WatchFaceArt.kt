package com.coverflow.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.coverflow.shared.CoverPalette
import com.coverflow.shared.FaceArtPacket
import com.coverflow.shared.FaceBackdrop
import com.coverflow.shared.Paths
import com.coverflow.shared.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Makes what a watch face shows while a song plays (the LiquiWatch · Expedition face): the
 * cover blurred and darkened, at the size the watch hands the face, its colour toned as Now
 * Playing's sliders, and the cover round for the face's date pill, at the very size the face
 * shows it ([FaceBackdrop]). The phone does all the work on the pictures, so the watch only
 * stores them and shows them: it never blurs, resizes or compresses one itself. A watch whose
 * face shows the album art gets the next song in the queue's a few seconds into the song before
 * it ([prepare]), kept aside so the face switches the moment that song starts; and any other
 * song's as it starts ([songChanged]), unless it was sent lately: the watch keeps the last few
 * songs', so a song played again goes up from what it has ([kept]). It can also ask (when it
 * hears of a song before it's come). When a sharper copy of a cover turns up (or a wrong one is
 * put right) the watches showing it, or keeping it aside, get it again.
 *
 * Those watches also hear what the face needs of the player's state ([faceState]) in a small
 * message of their own, the moment it changes, ahead of the synced state item.
 */
object WatchFaceArt {
    /**
     * Good enough that the soft gradients stay smooth once the face scales the picture up (a
     * little higher than it was at twice the size: each of its blocks covers more of the screen),
     * and well under a message's size (about 3 KB).
     */
    private const val QUALITY = 95

    /** The watches whose face has asked for a background (node id -> when it last did). */
    private const val PREFS = "watch_face_art"

    /** A watch gets each new song's background as it starts if its face asked within this many days. */
    private const val FACE_DAYS = 45L

    /** What each watch's face shows (node id -> art key). */
    private val showing = HashMap<String, String>()

    /** The next song's background each watch was sent ahead and keeps aside (node id -> art key). */
    private val sentNext = HashMap<String, String>()

    /**
     * The backgrounds each watch keeps (node id -> art keys, the latest last): every one sent to
     * it lately, as the watch keeps its last eight songs' (see FaceArt there), so a song played
     * again goes up from what the watch has and isn't sent again. Fewer than the watch keeps, so
     * one noted here is still there; should it not be after all (the watch app's data cleared),
     * the watch asks for it, and gets it ([serve]).
     */
    private val kept = HashMap<String, LinkedHashSet<String>>()
    private const val KEPT = 6

    private fun noteKept(node: String, key: String) = synchronized(showing) {
        val keys = kept.getOrPut(node) { LinkedHashSet() }
        keys.remove(key)
        keys.add(key)
        while (keys.size > KEPT) keys.remove(keys.first())
    }

    private fun keeps(node: String, key: String): Boolean = synchronized(showing) { kept[node]?.contains(key) == true }

    /**
     * How far into a song (ms) the next song's background goes to the watches: not at the very
     * moment this song's goes up on the face, when the watch is busiest.
     */
    private const val AHEAD_MS = 4_000L

    /** The next song's background on its way to the watches ([prepare]); on the main thread. */
    private var prepareJob: Job? = null

    /** The last few packets made (art key and cover -> packet), for watches asking again. */
    private val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 8
    }

    /** The watches whose face has shown the album art lately (node ids). */
    private fun faceNodes(ctx: Context): Set<String> {
        val since = System.currentTimeMillis() - FACE_DAYS * 86_400_000L
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .filter { (it.value as? Long ?: 0L) >= since }.keys
    }

    // What the faces were last told (faceState): the song's key, whether it plays or is between
    // songs, its length and speed, and when it started (wall-clock ms, while it plays).
    private var toldFace: String? = null
    private var toldStart = 0L

    /** A song's start moving by more than this (ms) is a seek: the faces hear of it. */
    private const val SEEK_MS = 500L

    /**
     * The player's state changed ([s]; on the main thread): if what a face needs of it changed (a
     * new song, play, pause, between songs, a skip or a seek), the watches whose face shows the
     * album art hear it now ([Paths.FACE_STATE]), ahead of the synced state item.
     */
    fun faceState(ctx: Context, s: PlayerState) {
        val told = "${s.current?.artKey.orEmpty()}|${s.isPlaying}|${s.between}|${s.current?.durationMs ?: 0L}|${s.speed}|" +
            "${s.current?.title.orEmpty()}|${s.current?.artist.orEmpty()}"
        val start = if (s.isPlaying) s.capturedAt - (s.positionMs / s.speed.coerceAtLeast(0.01f)).toLong() else 0L
        if (told == toldFace && abs(start - toldStart) < SEEK_MS) return
        toldFace = told
        toldStart = start
        val nodes = faceNodes(ctx)
        if (nodes.isEmpty()) return
        val payload = s.forFace().toJsonString().toByteArray(Charsets.UTF_8)
        AppScope.launch { nodes.forEach { WatchSync.send(ctx, it, Paths.FACE_STATE, payload) } }
    }

    /**
     * The background for [key] (the next song in the queue) made now, while the music plays, and
     * sent a few seconds later to the watches whose face shows the album art, which keep it aside
     * for when that song starts. (Its cover is looked for too, if it isn't saved yet.)
     */
    fun prepare(ctx: Context, key: String) {
        if (key.isBlank()) return
        val nodes = faceNodes(ctx)
        if (nodes.isEmpty()) return
        // (Only the latest next song goes: the watch keeps one aside.)
        prepareJob?.cancel()
        prepareJob = AppScope.launch {
            // Made now, ready should the song be skipped to; sent once the wait is over, made
            // again then (the same, unless a sharper copy of its cover turned up meanwhile).
            ArtStore.forWatch(ctx, key, 0)?.let { packet(key, it) }
            delay(AHEAD_MS)
            val master = ArtStore.forWatch(ctx, key, 0) ?: return@launch
            val packet = packet(key, master) ?: return@launch
            for (node in nodes) {
                val skip = synchronized(showing) {
                    val have = showing[node] == key || sentNext[node] == key || kept[node]?.contains(key) == true
                    if (!have) sentNext[node] = key
                    have
                }
                if (!skip) {
                    WatchSync.send(ctx, node, Paths.FACE_NEXT_PREFIX + key, packet)
                    noteKept(node, key)
                }
            }
        }
    }

    /** A watch's face shows [key] (it asked): send it its background and colour. */
    suspend fun serve(ctx: Context, nodeId: String, key: String) {
        if (key.isBlank()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(nodeId, System.currentTimeMillis()).apply()
        send(ctx, nodeId, key, asked = true)
    }

    /**
     * A new song started ([key]): the watches whose face shows the album art get its background
     * and colour now, without waiting to hear of the song and ask. If its cover isn't saved yet,
     * it goes as soon as it is ([coverChanged]).
     */
    fun songChanged(ctx: Context, key: String, aheadKey: String = "") {
        if (key.isBlank()) return
        val nodes = faceNodes(ctx).filter { synchronized(showing) { showing[it] } != key }
        if (nodes.isEmpty()) return
        AppScope.launch { nodes.forEach { send(ctx, it, key, aheadKey = aheadKey) } }
    }

    /**
     * Sends [nodeId] [key]'s background, unless it was sent ahead (as the next song's, maybe under
     * [aheadKey]: see PlayerState.aheadKey), or lately (as a song played again: [kept]), which the
     * watch puts up by itself as the song starts; sent all the same when the watch [asked].
     */
    private suspend fun send(ctx: Context, nodeId: String, key: String, asked: Boolean = false, aheadKey: String = "") {
        val ahead = synchronized(showing) {
            showing[nodeId] = key
            val sent = sentNext[nodeId]
            (sent == key || (aheadKey.isNotEmpty() && sent == aheadKey)).also { if (it) sentNext.remove(nodeId) }
        }
        if ((ahead || keeps(nodeId, key)) && !asked) return
        val master = ArtStore.forWatch(ctx, key, 0) ?: return
        val packet = packet(key, master) ?: return
        WatchSync.send(ctx, nodeId, Paths.FACE_ART_PREFIX + key, packet)
        noteKept(nodeId, key)
    }

    /**
     * A new cover for [key] ([master], the saved JPEG): the faces showing it get it again, and the
     * watches keeping it aside as the next song's.
     */
    suspend fun coverChanged(ctx: Context, key: String, master: ByteArray) {
        val (nodes, ahead) = synchronized(showing) {
            // (A watch keeping the old one from a while ago gets the new one when it next plays.)
            kept.values.forEach { it.remove(key) }
            showing.filterValues { it == key }.keys.toList() to sentNext.filterValues { it == key }.keys.toList()
        }
        if (nodes.isEmpty() && ahead.isEmpty()) return
        val packet = packet(key, master) ?: return
        nodes.forEach { WatchSync.send(ctx, it, Paths.FACE_ART_PREFIX + key, packet); noteKept(it, key) }
        ahead.forEach { WatchSync.send(ctx, it, Paths.FACE_NEXT_PREFIX + key, packet); noteKept(it, key) }
    }

    private suspend fun packet(key: String, master: ByteArray): ByteArray? {
        val id = "$key|${master.contentHashCode()}"
        synchronized(cache) { cache[id] }?.let { return it }
        val made = withContext(Dispatchers.Default) { runCatching { build(master) }.getOrNull() } ?: return null
        synchronized(cache) { cache[id] = made }
        return made
    }

    private fun build(master: ByteArray): ByteArray? {
        val cover = BitmapFactory.decodeByteArray(master, 0, master.size) ?: return null
        try {
            // The cover's colour, picked as for the watch's covers (WatchCovers), then toned.
            val side = minOf(cover.width, cover.height)
            if (side <= 0) return null
            val square = if (cover.width != cover.height) {
                Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
            } else cover
            val s = CoverPalette.SAMPLE
            val sample = Bitmap.createScaledBitmap(square, s, s, true)
            val px = IntArray(s * s)
            sample.getPixels(px, 0, s, 0, 0, s, s)
            if (sample !== square) sample.recycle()
            if (square !== cover) square.recycle()
            val tone = FaceBackdrop.tone(CoverPalette.dominant(px))

            val background = FaceBackdrop.blurred(cover)
            val image = ByteArrayOutputStream().use { out ->
                val ok = background.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                background.recycle()
                if (!ok) return null
                out.toByteArray()
            }
            // The cover round, for the face's date pill (a PNG: its corners are clear; a few KB).
            val round = runCatching {
                val r = FaceBackdrop.round(cover)
                ByteArrayOutputStream().use { out ->
                    val ok = r.compress(Bitmap.CompressFormat.PNG, 100, out)
                    r.recycle()
                    if (ok) out.toByteArray() else null
                }
            }.getOrNull()
            return FaceArtPacket(image, tone, round).toBytes()
        } finally {
            cover.recycle()
        }
    }
}
