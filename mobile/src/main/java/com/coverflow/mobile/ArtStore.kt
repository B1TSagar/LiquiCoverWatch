package com.coverflow.mobile

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import com.coverflow.shared.ArtRequest
import com.coverflow.shared.TextMatch
import com.coverflow.shared.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Cover art for the watch, keyed by [Track.artKey]. Sources, best first:
 *  1. the player's now-playing artwork
 *  2. artwork the player attached to queue items, or its link to the artwork
 *  3. the iTunes catalog (if online lookup is on)
 *
 * Players don't always attach the right picture, so every image is checked before it's used:
 *  - Stale art: right after a song change, many players still attach the previous song's
 *    cover for a moment. Art identical to the previous song's is ignored until the real one comes.
 *  - Placeholders: a generic image (app logo, grey note) shows up on songs by different artists.
 *    The first time the same picture appears for a second artist it's blacklisted for good,
 *    every cover that used it is deleted here and on the watch, and the catalog fills in.
 *  - Flat images (a single colour) are treated as placeholders: a real cover or the catalog wins.
 *
 * Sharpness: players often attach only a small thumbnail to the songs in their list, far below
 * what the watch shows. A thumbnail is sent straight away so the covers fill in quickly, then a
 * sharper copy of the same picture is looked for in the background: the player's own link to
 * the image (asking its image server for a bigger size where we know how), then the catalog.
 * A copy is only used if it looks like the same picture as the thumbnail ([CoverLook]), so a
 * catalog mismatch can never swap in the wrong cover. Sharper copies replace the saved one and
 * are pushed to the watch.
 */
object ArtStore {
    /** Largest cover edge (px) sent to the watch. */
    const val SIZE = 400

    /** Covers smaller than this are thumbnails worth replacing. */
    private const val SHARP = ArtRequest.SHARP_SIDE

    /** Data Layer messages should stay under 100 KB. */
    private const val MAX_BYTES = 92_000

    /** Don't look for a sharper copy of the same cover more often than this. */
    private const val UPGRADE_RETRY_MS = 30 * 60_000L

    /**
     * v3 cache: covers are saved at their real size and fingerprinted independently of size.
     * Older caches ("art", "covers") hold smaller covers with old fingerprints, so they're wiped.
     */
    private const val DIR = "covers_v3"
    private val OLD_DIRS = listOf("art", "covers")
    private const val INDEX = "coverflow_covers_v3"
    private const val OLD_INDEX = "coverflow_covers"
    private const val GENERIC = "generic_art_v3"
    private const val OLD_GENERIC = "generic_art"

    /** Covers kept on the phone (oldest saved go first; the watch keeps its own 800). */
    private const val MAX_COVERS = 2_000
    private const val TRIM_EVERY = 100

    private class Source(val bitmap: Bitmap?, val uri: String?)

    /**
     * A cover ready to send: JPEG bytes, its edge in px, what it looks like, and the hash it's
     * known by (a saved cover keeps the hash it was saved under).
     */
    private class Prepared(val bytes: ByteArray, val side: Int, val look: CoverLook, val hash: Long = look.hash) {
        val flat get() = look.flat
    }

    private val sources = object : LinkedHashMap<String, Source>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Source>?) = size > 200
    }
    private val hints = HashMap<String, Track>()
    private val misses = HashMap<String, Long>()
    private val upgradeTried = HashMap<String, Long>()
    private val hashArtists = HashMap<Long, MutableSet<String>>()
    private val netGate = Semaphore(2)
    private val writeLock = Mutex()

    // Now-playing bookkeeping (only touched under writeLock)
    private var playerKey: String? = null
    private var playerHash: Long? = null
    private var staleHash: Long? = null

    // Skip re-processing the same picture on every state publish (the player hands a new Bitmap each time)
    private var lastBitmap: Bitmap? = null
    private var lastBitmapKey: String? = null

    @Volatile
    private var oldCacheCleared = false

    private fun dir(ctx: Context): File {
        if (!oldCacheCleared) {
            oldCacheCleared = true
            for (old in OLD_DIRS) runCatching { File(ctx.filesDir, old).deleteRecursively() }
            runCatching { ctx.deleteSharedPreferences(OLD_INDEX) }
            runCatching { settings(ctx).edit().remove(OLD_GENERIC).apply() }
        }
        return File(ctx.filesDir, DIR).apply { mkdirs() }
    }

    private fun file(ctx: Context, key: String) = File(dir(ctx), "$key.jpg")

    /** Which picture (hash) each saved cover is, so a blacklisted placeholder can be found and purged. */
    private fun index(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(INDEX, Context.MODE_PRIVATE)

    /** For covers that replaced a thumbnail: the thumbnail's hash (it's the same picture). */
    private fun fromIndex(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("${INDEX}_from", Context.MODE_PRIVATE)

    private fun settings(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("coverflow", Context.MODE_PRIVATE)

    private fun savedHash(ctx: Context, key: String): Long? =
        index(ctx).let { if (it.contains(key)) it.getLong(key, 0L) else null }

    private fun replacedHash(ctx: Context, key: String): Long? =
        fromIndex(ctx).let { if (it.contains(key)) it.getLong(key, 0L) else null }

    private fun isGeneric(ctx: Context, hash: Long): Boolean =
        settings(ctx).getStringSet(GENERIC, null)?.contains(hash.toString()) == true

    // ---- Registration (main thread) ---------------------------------------------------------

    /** Remember which song a key belongs to, so we can search the catalog for it later. */
    @Synchronized
    fun hint(track: Track) {
        if (track.artKey.isNotBlank() && !hints.containsKey(track.artKey)) hints[track.artKey] = track
    }

    /** Register artwork the player exposed for a queue item. Loaded lazily when the watch asks. */
    @Synchronized
    fun offer(key: String, bitmap: Bitmap?, uri: String?) {
        if (key.isBlank() || (bitmap == null && uri.isNullOrBlank())) return
        val old = sources[key]
        sources[key] = Source(bitmap ?: old?.bitmap, uri?.takeIf { it.isNotBlank() } ?: old?.uri)
    }

    /** The player's now-playing artwork for [track]. Checked, saved and pushed to the watch. */
    fun fromPlayer(ctx: Context, track: Track, bitmap: Bitmap) {
        if (track.artKey.isBlank()) return
        synchronized(this) {
            val last = lastBitmap
            if (lastBitmapKey == track.artKey && last != null && (last === bitmap || (!last.isRecycled && last.sameAs(bitmap)))) return
            lastBitmapKey = track.artKey
            lastBitmap = bitmap
        }
        AppScope.launch { writeLock.withLock { handlePlayerArt(ctx, track, bitmap) } }
    }

    private suspend fun handlePlayerArt(ctx: Context, track: Track, bitmap: Bitmap) {
        val key = track.artKey
        val art = prepare(bitmap) ?: return
        if (isGeneric(ctx, art.hash)) return

        if (key != playerKey) {
            // A new song: remember the previous song's picture to catch stale art.
            staleHash = playerHash
            playerKey = key
            playerHash = null
        }
        if (art.hash == staleHash) return
        playerHash = art.hash

        if (noteUse(ctx, art.hash, key, track)) return

        val f = file(ctx, key)
        if (art.flat) {
            // Probably a placeholder: keep a real cover if we have one, else prefer the catalog.
            if (f.exists()) return
            fromCatalog(ctx, key)?.let { catalog ->
                save(ctx, key, catalog)
                WatchSync.pushArt(ctx, key, catalog.bytes)
                return
            }
        }
        if (f.exists()) {
            // Already saved: this picture, or a sharper copy of it found earlier.
            if (savedHash(ctx, key) == art.hash || replacedHash(ctx, key) == art.hash) return
            val saved = readSaved(ctx, key)
            if (saved != null && saved.side > art.side && saved.look.sameAs(art.look)) return
        }
        save(ctx, key, art)
        WatchSync.pushArt(ctx, key, art.bytes)
        if (art.side < SHARP) upgradeLater(ctx, key, art, tryLink = true)
    }

    // ---- Watch requests ---------------------------------------------------------------------

    /**
     * The cover for the watch, or null if there's none, or nothing sharper than the copy it has
     * ([haveSide] px, 0 = none). Thumbnails are sent anyway so the list fills in quickly, and a
     * sharper copy is looked for in the background (pushed to the watch when found).
     */
    suspend fun forWatch(ctx: Context, key: String, haveSide: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext null
        val f = file(ctx, key)
        if (f.exists()) {
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@withContext null
            val side = jpegSide(bytes)
            if (side in 1 until SHARP && mayUpgrade(key)) {
                readSaved(ctx, key, bytes)?.let { if (!it.flat) upgradeLater(ctx, key, it, tryLink = true) }
            }
            return@withContext bytes.takeIf { side > haveSide }
        }

        val (art, tried) = resolve(ctx, key) ?: return@withContext null
        val saved = writeLock.withLock {
            if (f.exists()) {
                null // now-playing art arrived meanwhile; it wins
            } else {
                save(ctx, key, art)
                art
            }
        }
        if (saved == null) return@withContext runCatching { f.readBytes() }.getOrNull()
        if (art.side < SHARP && !art.flat && tried != Tried.CATALOG) {
            upgradeLater(ctx, key, art, tryLink = tried == Tried.BITMAP)
        }
        art.bytes.takeIf { art.side > haveSide }
    }

    /** How far down the source list [resolve] had to go. */
    private enum class Tried { BITMAP, LINK, CATALOG }

    private suspend fun resolve(ctx: Context, key: String): Pair<Prepared, Tried>? {
        val src = synchronized(this) { sources[key] }
        val hint = synchronized(this) { hints[key] }

        src?.bitmap?.let { prepare(it) }?.let { if (usable(ctx, it, key, hint)) return it to Tried.BITMAP }
        src?.uri?.let { loadLink(ctx, it) }?.let { if (usable(ctx, it, key, hint)) return it to Tried.LINK }
        return fromCatalog(ctx, key)?.let { it to Tried.CATALOG }
    }

    private suspend fun usable(ctx: Context, art: Prepared, key: String, track: Track?): Boolean =
        !art.flat && !isGeneric(ctx, art.hash) && !noteUse(ctx, art.hash, key, track)

    // ---- Sharper copies ---------------------------------------------------------------------

    private fun mayUpgrade(key: String): Boolean = synchronized(this) {
        val last = upgradeTried[key]
        last == null || SystemClock.elapsedRealtime() - last >= UPGRADE_RETRY_MS
    }

    /**
     * Look for a sharper copy of [small] (the cover saved for [key]) in the background. If one
     * turns up and [small] is still the saved cover, it replaces it and goes to the watch.
     */
    private fun upgradeLater(ctx: Context, key: String, small: Prepared, tryLink: Boolean) {
        synchronized(this) {
            if (!mayUpgrade(key)) return
            upgradeTried[key] = SystemClock.elapsedRealtime()
        }
        AppScope.launch {
            val sharp = sharperCopy(ctx, key, small, tryLink) ?: return@launch
            writeLock.withLock {
                // Something else may have been saved meanwhile (e.g. new now-playing art).
                if (savedHash(ctx, key) != small.hash) return@withLock
                save(ctx, key, sharp, replaced = small.hash)
                WatchSync.pushArt(ctx, key, sharp.bytes)
            }
        }
    }

    private suspend fun sharperCopy(ctx: Context, key: String, small: Prepared, tryLink: Boolean): Prepared? {
        fun better(p: Prepared?) = p != null && p.side > small.side && !isGeneric(ctx, p.hash) && p.look.sameAs(small.look)
        if (tryLink) {
            val link = synchronized(this) { sources[key]?.uri }
            val fromLink = link?.let { loadLink(ctx, it) }
            if (better(fromLink)) return fromLink
        }
        val catalog = fromCatalog(ctx, key)
        return catalog.takeIf { better(it) }
    }

    // ---- Loading ----------------------------------------------------------------------------

    private suspend fun fromCatalog(ctx: Context, key: String): Prepared? {
        if (!Prefs.onlineLookup(ctx)) return null
        val hint = synchronized(this) { hints[key] } ?: return null
        val now = SystemClock.elapsedRealtime()
        val missedAt = synchronized(this) { misses[key] }
        if (missedAt != null && now - missedAt < 30 * 60_000L) return null
        val art = netGate.withPermit {
            ITunes.artworkUrl(hint.artist, hint.album, hint.title, SIZE)
                ?.let { Http.get(it) }
                ?.let { decodeBytes(it) }
                ?.let { prepare(it) }
        }
        if (art == null) synchronized(this) { misses[key] = now }
        return art
    }

    /** The image behind a player's artwork link, asking for a bigger copy first where we know how. */
    private suspend fun loadLink(ctx: Context, uri: String): Prepared? {
        for (candidate in ArtLinks.sharper(uri, SIZE) + uri) {
            loadUri(ctx, candidate)?.let { return it }
        }
        return null
    }

    private suspend fun loadUri(ctx: Context, uri: String): Prepared? = try {
        val raw = if (uri.startsWith("https://") || uri.startsWith("http://")) {
            netGate.withPermit { Http.get(uri) }
        } else {
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        }
        raw?.let { decodeBytes(it) }?.let { prepare(it) }
    } catch (e: Exception) {
        null // e.g. a content:// URI the player doesn't share with other apps
    }

    /** The saved cover for [key] as a [Prepared], with the hash it was saved under. */
    private fun readSaved(ctx: Context, key: String, bytes: ByteArray? = null): Prepared? {
        val raw = bytes ?: runCatching { file(ctx, key).readBytes() }.getOrNull() ?: return null
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val look = lookOf(bitmap)
        val side = minOf(bitmap.width, bitmap.height)
        bitmap.recycle()
        // Re-decoding a JPEG shifts colours a hair, which can change the hash: keep the saved one.
        return Prepared(raw, side, look, savedHash(ctx, key) ?: look.hash)
    }

    // ---- Placeholder detection --------------------------------------------------------------

    /**
     * Records that the picture [hash] was used for [track]. One picture on two different albums
     * (or, without an album, by two different artists) is a placeholder: it's blacklisted and
     * purged. Returns true if so. (Albums, not artists: a soundtrack's tracks credit different
     * singers first but share one real cover.)
     */
    private suspend fun noteUse(ctx: Context, hash: Long, key: String, track: Track?): Boolean {
        val who = TextMatch.loose(track?.album.orEmpty()).ifBlank { TextMatch.primaryArtist(track?.artist.orEmpty()) }
            .ifBlank { "key:$key" }
        val artists = synchronized(this) {
            hashArtists.getOrPut(hash) { HashSet() }.apply { add(who) }.size
        }
        if (artists < 2) return false
        markGeneric(ctx, hash)
        return true
    }

    private suspend fun markGeneric(ctx: Context, hash: Long) {
        val prefs = settings(ctx)
        val generic = HashSet(prefs.getStringSet(GENERIC, null) ?: emptySet())
        if (!generic.add(hash.toString())) return
        prefs.edit().putStringSet(GENERIC, generic).apply()

        // Purge every cover that was saved with this picture (or a sharper copy of it),
        // here and on the watch.
        val affected = HashSet<String>()
        for ((key, value) in index(ctx).all) if (value == hash) affected.add(key)
        for ((key, value) in fromIndex(ctx).all) if (value == hash) affected.add(key)
        if (affected.isEmpty()) return
        val edit = index(ctx).edit()
        val editFrom = fromIndex(ctx).edit()
        for (key in affected) {
            runCatching { file(ctx, key).delete() }
            edit.remove(key)
            editFrom.remove(key)
        }
        edit.apply()
        editFrom.apply()
        for (key in affected) WatchSync.dropArt(ctx, key)
    }

    // ---- Image helpers ----------------------------------------------------------------------

    /** Saves [art] for [key]. [replaced] is the hash of the thumbnail it's a sharper copy of. */
    private fun save(ctx: Context, key: String, art: Prepared, replaced: Long? = null) {
        // Written aside and renamed in, so a watch request reading it never gets half a picture.
        val f = file(ctx, key)
        val tmp = File(f.path + ".tmp")
        runCatching { tmp.writeBytes(art.bytes); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } }
        index(ctx).edit().putLong(key, art.hash).apply()
        val from = fromIndex(ctx).edit()
        if (replaced != null) from.putLong(key, replaced) else from.remove(key)
        from.apply()
        if (++saves % TRIM_EVERY == 1) trim(ctx)
    }

    private var saves = 0

    /** Keeps the newest [MAX_COVERS] covers (a dropped one is fetched again if it's ever needed). */
    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".jpg") } ?: return
        if (files.size <= MAX_COVERS) return
        val edit = index(ctx).edit()
        val editFrom = fromIndex(ctx).edit()
        for (f in files.sortedBy { it.lastModified() }.take(files.size - MAX_COVERS)) {
            val key = f.name.removeSuffix(".jpg")
            f.delete()
            edit.remove(key)
            editFrom.remove(key)
        }
        edit.apply()
        editFrom.apply()
    }

    /** Edge of a JPEG in px, read from its header only. */
    private fun jpegSide(bytes: ByteArray): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return minOf(bounds.outWidth, bounds.outHeight)
    }

    private fun decodeBytes(raw: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Center-crop to square, scale down to at most [SIZE], JPEG under [MAX_BYTES], and fingerprint. */
    private fun prepare(source: Bitmap): Prepared? {
        return try {
            var b = source
            if (b.config == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val side = minOf(b.width, b.height)
            if (side <= 0) return null
            val square = if (b.width != b.height) {
                Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
            } else b
            val scaled = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
            val look = lookOf(scaled)
            val (bytes, edge) = encode(scaled) ?: return null
            Prepared(bytes, edge, look)
        } catch (t: Throwable) {
            null
        }
    }

    /** JPEG bytes and edge: the best quality that fits a message, shrinking very detailed covers a little. */
    private fun encode(image: Bitmap): Pair<ByteArray, Int>? {
        var b = image
        repeat(3) {
            for (quality in intArrayOf(92, 86, 80, 74)) {
                val bytes = ByteArrayOutputStream().use { out ->
                    b.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
                if (bytes.size <= MAX_BYTES) return bytes to b.width
            }
            val smaller = (b.width * 0.85f).toInt().coerceAtLeast(64)
            b = Bitmap.createScaledBitmap(b, smaller, smaller, true)
        }
        return null
    }

    private fun lookOf(b: Bitmap): CoverLook {
        // Hardware bitmaps can't be read pixel by pixel; copy those first.
        val bitmap = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) ?: b else b
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return CoverLook.of(pixels, bitmap.width, bitmap.height)
    }
}
