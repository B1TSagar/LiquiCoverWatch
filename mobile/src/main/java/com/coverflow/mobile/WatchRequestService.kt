package com.coverflow.mobile

import android.content.Context
import com.coverflow.shared.AlbumRequest
import com.coverflow.shared.AlbumResponse
import com.coverflow.shared.ArtRequest
import com.coverflow.shared.CollectionRequest
import com.coverflow.shared.CollectionResponse
import com.coverflow.shared.Command
import com.coverflow.shared.CoverBatchRequest
import com.coverflow.shared.NO_QUEUE_ID
import com.coverflow.shared.Paths
import com.coverflow.shared.RatingRequest
import com.coverflow.shared.TextMatch
import com.coverflow.shared.Track
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.launch

/** Handles messages from the watch: commands, cover requests (one, or a batch) and album requests. */
class WatchRequestService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val ctx = applicationContext
        MediaHub.start(ctx)
        val from = messageEvent.sourceNodeId
        val data = messageEvent.data
        when (messageEvent.path) {
            Paths.CMD -> Command.fromBytes(data)?.let { MediaHub.execute(ctx, it) }

            // The watch checking its clock against ours: answer with when it arrived and when we answer.
            Paths.CLOCK -> {
                val arrived = System.currentTimeMillis()
                val sent = String(data, Charsets.UTF_8).trim()
                AppScope.launch {
                    val reply = "$sent,$arrived,${System.currentTimeMillis()}"
                    WatchSync.send(ctx, from, Paths.CLOCK, reply.toByteArray(Charsets.UTF_8))
                }
            }

            Paths.REQ_ART -> {
                val request = ArtRequest.fromBytes(data)
                AppScope.launch { WatchCovers.serveOne(ctx, from, request) }
            }

            Paths.REQ_COVERS -> CoverBatchRequest.fromBytes(data)?.let { WatchCovers.serveBatch(ctx, from, it) }

            // A watch face showing a song: its blurred background and colour, made here.
            Paths.REQ_FACE_ART -> {
                val key = String(data, Charsets.UTF_8).trim()
                AppScope.launch { WatchFaceArt.serve(ctx, from, key) }
            }

            Paths.REQ_ALBUM -> {
                val request = AlbumRequest.fromBytes(data) ?: return
                AppScope.launch {
                    val response = AlbumLookup.build(ctx, request)
                    WatchSync.send(ctx, from, Paths.ALBUM, response.toBytes())
                }
            }

            // Recommended or liked songs from the app that played last (the watch's other cover displays).
            Paths.REQ_COLLECTION -> {
                val request = CollectionRequest.fromBytes(data) ?: return
                AppScope.launch {
                    val response = runCatching { Collections.build(ctx, request) }.getOrElse {
                        CollectionResponse(request.kind, request.pkg, "", emptyList(), "Couldn't get the songs (${it.javaClass.simpleName}).")
                    }
                    WatchSync.send(ctx, from, Paths.COLLECTION, response.toBytes())
                }
            }

            Paths.REQ_LYRICS -> {
                val track = Track.fromBytes(data) ?: return
                AppScope.launch {
                    val lyrics = LyricsLookup.get(ctx, track)
                    WatchSync.send(ctx, from, Paths.LYRICS, lyrics.toBytes())
                }
            }

            Paths.REQ_RATING -> {
                val request = RatingRequest.fromBytes(data) ?: return
                AppScope.launch {
                    val change = request.change
                    val result = if (change != null) {
                        Ratings.apply(ctx, request.track, change)
                    } else {
                        Ratings.current(ctx, request.track)
                    }
                    WatchSync.send(ctx, from, Paths.RATING, result.toBytes())
                }
            }
        }
    }
}

/**
 * Builds the track list shown on the back of a flipped cover. The catalog is looked up afresh
 * each time an album is opened, and the fullest list each album has had is kept on the phone
 * ([AlbumStore]): so an album opened from the recently played songs (when the player's queue
 * can't help, and the catalog may not be reachable) still shows all its tracks, including any
 * the player had in its own album queue that the catalog edition lacks.
 */
object AlbumLookup {
    fun build(ctx: Context, request: AlbumRequest): AlbumResponse {
        val cover = request.cover
        val state = MediaHub.state.value

        // Songs from this album that the player already knows about (they can be played directly).
        val known = buildList {
            state?.list?.let { addAll(it) }
            state?.current?.let { add(it) }
            add(cover)
        }.filter { t ->
            t.sameSongAs(cover) ||
                (cover.album.isNotBlank() && TextMatch.loose(t.album) == TextMatch.loose(cover.album))
        }.distinctBy { it.id }

        // A fresh look at the catalog (tried twice: a slow or dropped connection otherwise
        // leaves only the songs the player knows).
        val online = if (Prefs.onlineLookup(ctx) && (cover.album.isNotBlank() || cover.title.isNotBlank())) {
            (1..2).firstNotNullOfOrNull { attempt ->
                if (attempt > 1) Thread.sleep(1_000)
                runCatching { ITunes.albumTracks(cover.artist, cover.album, cover.title) }.getOrNull()
            }
        } else null

        val fresh = online?.let { (info, items) ->
            val tracks = items.map { o ->
                Track(
                    title = o.optString("trackName"),
                    artist = o.optString("artistName"),
                    album = o.optString("collectionName"),
                    artKey = cover.artKey,
                    durationMs = o.optLong("trackTimeMillis", 0L),
                    trackNumber = o.optInt("trackNumber", 0),
                    source = cover.source,
                    sourceName = cover.sourceName,
                )
            }
            AlbumResponse(
                key = request.key,
                album = info.optString("collectionName").ifBlank { cover.album },
                artist = info.optString("artistName").ifBlank { cover.artist },
                tracks = tracks,
                fromCatalog = true,
            )
        }
        val kept = AlbumStore.get(ctx, request.key)

        // The fullest list: the fresh one (or, if the catalog couldn't be reached, the copy kept
        // from before), with any songs of the album the other has, or the player knows, that it
        // lacks (bonus tracks, a fuller edition, the player's own album queue).
        val base = fresh ?: kept
        val result = if (base != null) {
            // From the copy kept before, only songs of this very album (an earlier lookup may have
            // found another edition, or another album altogether).
            val names = setOf(TextMatch.loose(base.album), TextMatch.loose(cover.album)).filter { it.isNotEmpty() }
            val keptExtra = if (fresh != null) kept?.tracks.orEmpty().filter { TextMatch.loose(it.album) in names } else emptyList()
            // The same album name isn't enough (Queen's "Greatest Hits" and ABBA's): a song is only
            // added if its first artist is credited somewhere on the album (a soundtrack's singers are).
            val credited = (base.tracks.map { it.artist } + base.artist + cover.artist).joinToString(" ") { TextMatch.loose(it) }
            val extra = (keptExtra + known).filter { t ->
                t.sameSongAs(cover) || TextMatch.primaryArtist(t.artist).let { it.isEmpty() || credited.contains(it) }
            }
            var tracks = base.tracks
            var added = false
            for (t in extra) {
                if (tracks.none { it.sameSongAs(t) }) {
                    tracks = tracks + t
                    added = true
                }
            }
            if (added && tracks.all { it.trackNumber > 0 }) tracks = tracks.sortedBy { it.trackNumber }
            base.copy(tracks = tracks, fromCatalog = true)
        } else {
            AlbumResponse(key = request.key, album = cover.album, artist = cover.artist, tracks = known, fromCatalog = false)
        }

        // Ready to play from here: the player's own queue ids where it has the songs now (never
        // old ones kept from before, which could point at another song), and the app to play in.
        val ready = result.copy(
            tracks = result.tracks.map { t ->
                t.copy(
                    queueId = known.firstOrNull { it.sameSongAs(t) }?.queueId ?: NO_QUEUE_ID,
                    artKey = cover.artKey,
                    source = cover.source.ifBlank { t.source },
                    sourceName = cover.sourceName.ifBlank { t.sourceName },
                )
            },
        )
        if (ready.fromCatalog) AlbumStore.put(ctx, ready)
        return ready
    }
}

/** The fullest track list each album has had, kept in the phone's storage by album key. */
object AlbumStore {
    private const val MAX = 400

    private fun dir(ctx: Context) = File(ctx.filesDir, "albums").also { it.mkdirs() }

    private fun file(ctx: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return File(dir(ctx), digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    fun get(ctx: Context, key: String): AlbumResponse? = try {
        file(ctx, key).takeIf { it.exists() }?.readBytes()?.let { AlbumResponse.fromBytes(it) }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun put(ctx: Context, album: AlbumResponse) {
        try {
            file(ctx, album.key).writeBytes(album.toBytes())
            val files = dir(ctx).listFiles() ?: return
            if (files.size > MAX) files.sortedBy { it.lastModified() }.take(files.size - MAX).forEach { it.delete() }
        } catch (e: Exception) {
            // Best effort: the list is looked up again next time.
        }
    }
}
