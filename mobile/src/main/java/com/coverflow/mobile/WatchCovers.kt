package com.coverflow.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.coverflow.shared.ArtRequest
import com.coverflow.shared.CoverBatchRequest
import com.coverflow.shared.CoverPacket
import com.coverflow.shared.CoverPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Gets covers ready for the watch, so all it has to do is show them. From a saved cover (the
 * JPEG [ArtStore] keeps, up to 400 px) it makes a [CoverPacket]:
 *  - the picture cut to exactly the size the watch draws it at, as WebP (a fraction of the
 *    JPEG's bytes, so it crosses Bluetooth several times faster),
 *  - its dominant colour ([CoverPalette]).
 *
 * Batches from the watch are answered nearest-first by a few workers at once (so one cover that
 * needs a slow catalog lookup doesn't hold up the rest), and a newer batch from the same watch
 * goes first, with what the one still running hadn't sent after it. Recent packets are remembered.
 */
object WatchCovers {
    private const val DEFAULT_SIZE = 248
    private const val QUALITY = 86
    private const val WORKERS = 3
    private const val SIZE_PREF = "watch_cover_size"

    private val cache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 64
    }
    private val batches = HashMap<String, Job>()

    /** Per watch: covers asked for and not sent yet (under [batches]'s lock). */
    private val unsent = HashMap<String, LinkedHashMap<String, Int>>()

    @Volatile
    private var size = 0

    /** The edge (px) the watch draws covers at: as it last told us, remembered between runs. */
    fun size(ctx: Context): Int {
        if (size == 0) {
            size = ctx.getSharedPreferences("coverflow", Context.MODE_PRIVATE).getInt(SIZE_PREF, DEFAULT_SIZE)
        }
        return size
    }

    private fun noteSize(ctx: Context, requested: Int): Int {
        if (requested <= 0) return size(ctx)
        val s = requested.coerceIn(96, 480)
        if (s != size) {
            size = s
            ctx.getSharedPreferences("coverflow", Context.MODE_PRIVATE).edit().putInt(SIZE_PREF, s).apply()
        }
        return s
    }

    /** One cover the watch asked for. */
    suspend fun serveOne(ctx: Context, nodeId: String, request: ArtRequest) {
        val edge = noteSize(ctx, request.size)
        val master = ArtStore.forWatch(ctx, request.key, request.haveSide) ?: return
        val packet = packet(request.key, master, edge) ?: return
        WatchSync.sendCover(ctx, nodeId, request.key, packet)
    }

    /** Many covers the watch asked for, nearest to what it shows first. */
    fun serveBatch(ctx: Context, nodeId: String, request: CoverBatchRequest) {
        val edge = noteSize(ctx, request.size)
        synchronized(batches) {
            batches[nodeId]?.cancel()
            // What the replaced batch hadn't sent goes after the new one's: the watch won't ask
            // for it again for a while (and a long list comes as several batches).
            val items = LinkedHashMap<String, Int>()
            request.items.forEach { (key, have) -> items[key] = have }
            unsent[nodeId]?.forEach { (key, have) -> items.putIfAbsent(key, have) }
            unsent[nodeId] = items
            val list = items.toList()
            batches[nodeId] = AppScope.launch { runBatch(ctx, nodeId, list, edge) }
        }
    }

    private fun served(nodeId: String, key: String) = synchronized(batches) { unsent[nodeId]?.remove(key) }

    private suspend fun runBatch(ctx: Context, nodeId: String, items: List<Pair<String, Int>>, edge: Int) = coroutineScope {
        val queue = Channel<Pair<String, Int>>(Channel.UNLIMITED)
        items.forEach { queue.trySend(it) }
        queue.close()
        val ready = Channel<Pair<String, ByteArray>>(WORKERS)
        val workers = List(WORKERS) {
            launch {
                for ((key, have) in queue) {
                    val packet = ArtStore.forWatch(ctx, key, have)?.let { packet(key, it, edge) }
                    if (packet == null) served(nodeId, key) else ready.send(key to packet)
                }
            }
        }
        launch {
            workers.joinAll()
            ready.close()
        }
        // One message at a time keeps them in order and the Bluetooth link uncongested.
        for ((key, packet) in ready) {
            WatchSync.sendCover(ctx, nodeId, key, packet)
            served(nodeId, key)
        }
    }

    /** The packet for [key]'s saved cover [master] (JPEG), cut to [edge] px. */
    suspend fun packet(key: String, master: ByteArray, edge: Int): ByteArray? {
        val id = "$key|$edge|${master.contentHashCode()}"
        synchronized(cache) { cache[id] }?.let { return it }
        val made = withContext(Dispatchers.Default) { runCatching { build(master, edge) }.getOrNull() } ?: return null
        synchronized(cache) { cache[id] = made }
        return made
    }

    private fun build(master: ByteArray, edge: Int): ByteArray? {
        val made = ArrayList<Bitmap>(5)
        fun keep(b: Bitmap): Bitmap = b.also { if (made.none { m -> m === it }) made += it }
        try {
            val full = keep(BitmapFactory.decodeByteArray(master, 0, master.size) ?: return null)
            val side = minOf(full.width, full.height)
            if (side <= 0) return null
            val square = if (full.width != full.height) {
                keep(Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side))
            } else full

            // The picture, at the size it's shown (never blown up: a thumbnail stays as it is).
            val shownEdge = minOf(edge, side)
            val shown = if (shownEdge != side) keep(Bitmap.createScaledBitmap(square, shownEdge, shownEdge, true)) else square
            val image = ByteArrayOutputStream().use { out ->
                val format = if (Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                if (!shown.compress(format, QUALITY, out)) return null
                out.toByteArray()
            }

            // Its colour.
            val s = CoverPalette.SAMPLE
            val sample = keep(Bitmap.createScaledBitmap(square, s, s, true))
            val samplePx = IntArray(s * s)
            sample.getPixels(samplePx, 0, s, 0, 0, s, s)
            val color = CoverPalette.dominant(samplePx)

            return CoverPacket(image, color, side).toBytes()
        } finally {
            made.forEach { it.recycle() }
        }
    }
}
