package com.coverflow.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.service.media.MediaBrowserService
import com.coverflow.shared.CollectionKind
import com.coverflow.shared.CollectionRequest
import com.coverflow.shared.CollectionResponse
import com.coverflow.shared.NO_QUEUE_ID
import com.coverflow.shared.TextMatch
import com.coverflow.shared.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale

/**
 * The watch's other cover displays: songs recommended by the app that played last, and songs
 * liked in it.
 *
 *  - First, the app's own library, the one Android Auto browses: its recommendations, or its
 *    liked songs. Many apps only let Google's and the car makers' apps in, though.
 *  - Otherwise, **recommended**: YouTube Music's radio for the songs played in the app lately
 *    (what YouTube Music would play after them: its own, for YouTube Music; for any other app,
 *    a guide to songs like them, which that app then plays by name), else songs like the artists
 *    played lately from Deezer's free artist radio, else more songs by those artists from
 *    Apple's catalog.
 *  - Otherwise, **liked**: the songs liked in the app while Cover Flow was running (its heart,
 *    thumb or like button, seen as the song played), and songs given 3 stars or more on the
 *    watch, newest first ([LikedStore]).
 */
object Collections {
    private const val MAX_RECOMMENDED = 30
    private const val MAX_LIKED = 150

    suspend fun build(ctx: Context, request: CollectionRequest): CollectionResponse {
        val pkg = request.pkg.ifBlank { withContext(Dispatchers.Main) { MediaHub.lastAppPackage(ctx) }.orEmpty() }
        if (pkg.isBlank()) {
            return CollectionResponse(request.kind, "", "", emptyList(), "Play a song first: these songs come from the app that played last.")
        }
        val app = Players.label(ctx, pkg)
        // The app's own list, if it lets us in and what it gives are songs (not playlists or stations).
        val fromApp = runCatching { AppLibrary.load(ctx, pkg, app, request.kind) }.getOrNull().orEmpty().filter(::looksLikeSong)
        val result = if (request.kind == CollectionKind.LIKED) liked(ctx, pkg, app, fromApp) else recommended(ctx, pkg, app, fromApp)
        // Their covers: looked up like any song's when the watch asks.
        result.tracks.forEach { ArtStore.hint(it) }
        return result
    }

    private suspend fun liked(ctx: Context, pkg: String, app: String, fromApp: List<Track>): CollectionResponse {
        val seen = LikedStore.list(ctx, pkg)
        if (fromApp.isNotEmpty()) {
            val merged = fromApp + seen.filter { s -> fromApp.none { it.sameSongAs(s) } }
            return CollectionResponse(CollectionKind.LIKED, pkg, app, merged.take(MAX_LIKED), "$app's liked songs")
        }
        return if (seen.isNotEmpty()) {
            CollectionResponse(CollectionKind.LIKED, pkg, app, seen.take(MAX_LIKED), "Liked in $app while Cover Display ran ($app doesn't share its own list)")
        } else {
            CollectionResponse(
                CollectionKind.LIKED, pkg, app, emptyList(),
                "$app doesn't share its liked songs, so Cover Display collects them as you like them: in $app as a song plays, or with 3 stars or more on the watch.",
            )
        }
    }

    /** Words naming lists rather than songs ("Today's Hits Playlist", "Bollywood Radio Station"). */
    private val listWords = listOf("playlist", "station", "radio", "album", "episode", "podcast", "chart", "essentials", "mix")

    private fun looksLikeSong(t: Track): Boolean {
        val words = (t.title + " " + t.artist).lowercase(Locale.ROOT)
        return t.artist.isNotBlank() && listWords.none { it in words }
    }

    /** At least this many songs from the app's own recommendations to use them. */
    private const val MIN_APP_SONGS = 5

    private suspend fun recommended(ctx: Context, pkg: String, app: String, fromApp: List<Track>): CollectionResponse {
        if (fromApp.size >= MIN_APP_SONGS) {
            return CollectionResponse(CollectionKind.RECOMMENDED, pkg, app, fromApp.take(MAX_RECOMMENDED), "$app's recommendations")
        }
        if (!Prefs.onlineLookup(ctx)) {
            return CollectionResponse(CollectionKind.RECOMMENDED, pkg, app, emptyList(), "$app doesn't share its recommendations, and online lookups are off in Cover Display on the phone.")
        }
        val recent = withContext(Dispatchers.Main) { MediaHub.recentFrom(pkg) }
        if (recent.isEmpty()) {
            return CollectionResponse(CollectionKind.RECOMMENDED, pkg, app, emptyList(), "Play a few songs in $app first: the recommendations start from them.")
        }
        val played = recent.take(15)
        fun fresh(t: Track) = played.none { it.sameSongAs(t) }

        // YouTube Music's radio and Deezer's artist radio are asked at once (each can take a few
        // seconds); YouTube Music's is used when it has enough.
        val ytm = pkg == YouTubeMusicLinks.PACKAGE
        val (radio, deezer) = coroutineScope {
            val r = async { runCatching { youTubeMusicRadio(ctx, pkg, app, recent) }.getOrDefault(emptyList<Track>() to null) }
            val d = async { runCatching { DeezerRadio.like(ctx, recent, pkg, app) }.getOrDefault(emptyList<Track>() to emptyList()) }
            r.await() to d.await()
        }
        val (radioSongs, seed) = radio
        val fromRadio = radioSongs.filter(::fresh)
        if (fromRadio.size >= MIN_RADIO_SONGS || (fromRadio.isNotEmpty() && deezer.first.isEmpty())) {
            val note = if (ytm) "YouTube Music's radio for “${seed?.title.orEmpty()}”" else "Like “${seed?.title.orEmpty()}” (YouTube Music's radio), to play in $app"
            return CollectionResponse(CollectionKind.RECOMMENDED, pkg, app, fromRadio.take(MAX_RECOMMENDED), note)
        }
        val (deezerSongs, artists) = deezer
        val fromDeezer = deezerSongs.filter(::fresh)
        if (fromDeezer.isNotEmpty()) {
            return CollectionResponse(CollectionKind.RECOMMENDED, pkg, app, fromDeezer.take(MAX_RECOMMENDED), "Like ${artists.take(3).joinToString(", ")} (Deezer's artist radio), to play in $app")
        }
        // Last: more songs by the artists played lately, from Apple's catalog.
        val (more, by) = withContext(Dispatchers.IO) { moreByArtists(recent, pkg, app) }
        val fromCatalog = more.filter(::fresh)
        return if (fromCatalog.isNotEmpty()) {
            CollectionResponse(CollectionKind.RECOMMENDED, pkg, app, fromCatalog.take(MAX_RECOMMENDED), "More by ${by.take(3).joinToString(", ")}, to play in $app")
        } else {
            CollectionResponse(CollectionKind.RECOMMENDED, pkg, app, emptyList(), "Couldn't find songs like the ones played in $app (no connection?).")
        }
    }

    /** At least this many songs from YouTube Music's radio to prefer it. */
    private const val MIN_RADIO_SONGS = 8

    /** Songs by the artists of [recent] (newest first) from Apple's catalog, round-robin; and those artists. */
    private fun moreByArtists(recent: List<Track>, pkg: String, app: String): Pair<List<Track>, List<String>> {
        val artists = recent.map { it.artist }.filter { it.isNotBlank() }.distinctBy { TextMatch.primaryArtist(it) }.take(3)
        val lists = artists.map { a ->
            runCatching { ITunes.songsBy(a) }.getOrDefault(emptyList()).map { o ->
                val title = o.optString("trackName")
                val by = o.optString("artistName")
                val album = o.optString("collectionName")
                val t = Track(
                    title = title,
                    artist = by,
                    album = album,
                    artKey = TextMatch.artKey(by, album, title),
                    durationMs = o.optLong("trackTimeMillis", 0L),
                    source = pkg,
                    sourceName = app,
                )
                o.optString("artworkUrl100").takeIf { it.startsWith("http") }?.let { ArtStore.offer(t.artKey, null, it.replace(Regex("\\d+x\\d+bb"), "600x600bb")) }
                t
            }
        }
        val out = ArrayList<Track>()
        for (t in interleave(lists)) if (t.title.isNotBlank() && out.none { it.sameSongAs(t) }) out += t
        return out to artists.map { it.split(',').first().trim() }
    }

    /**
     * YouTube Music's radio for the last two songs played in [pkg] that have a known YouTube
     * Music video (the newest looked up if none has), merged; and the song it started from. For
     * another app, only its songs proper (with an album: not music videos, whose titles and
     * channels another app couldn't find), to play in that app.
     */
    private suspend fun youTubeMusicRadio(ctx: Context, pkg: String, app: String, recent: List<Track>): Pair<List<Track>, Track?> {
        val ytm = pkg == YouTubeMusicLinks.PACKAGE
        val seeds = recent.take(6)
            .mapNotNull { t -> YouTubeMusicLinks.known(ctx, t)?.let { t to it } }
            .take(2)
            .ifEmpty {
                val first = recent.first()
                val found = withTimeoutOrNull(12_000) { YouTubeMusicLinks.lookUp(ctx, first).await() }?.video
                listOfNotNull(found?.let { first to it })
            }
        if (seeds.isEmpty()) return emptyList<Track>() to null
        val lists = withContext(Dispatchers.IO) {
            seeds.map { (_, video) -> async { runCatching { YouTubeMusicLinks.radio(video) }.getOrDefault(emptyList()) } }.map { it.await() }
        }
        val out = ArrayList<Track>()
        for (song in interleave(lists)) {
            if (!ytm && song.album.isBlank()) continue
            val t = Track(
                title = song.title,
                artist = song.artist,
                album = song.album,
                artKey = TextMatch.artKey(song.artist, song.album, song.title),
                durationMs = song.durationMs,
                source = pkg,
                sourceName = app,
            )
            if (out.any { it.sameSongAs(t) }) continue
            out += t
            // Picked later in YouTube Music, it opens straight on this very video.
            YouTubeMusicLinks.remember(ctx, t, song.video)
            // Square song covers only (a music video's thumbnail is a wide frame of the video).
            song.cover?.takeIf { "googleusercontent" in it }?.let { ArtStore.offer(t.artKey, null, it) }
        }
        return out to seeds.first().first
    }

    /** Round-robin through [lists]: the best of each first. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (l in lists) l.getOrNull(i)?.let { out += it }
        return out
    }
}

/**
 * Songs liked in each app, newest first: seen liked in it as they played (its heart, thumb or
 * like button), or liked on the watch. Kept on the phone by app.
 */
object LikedStore {
    private const val MAX = 300

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("coverflow_liked", Context.MODE_PRIVATE)

    @Synchronized
    fun list(ctx: Context, pkg: String): List<Track> = try {
        Track.listFromJson(JSONArray(prefs(ctx).getString(pkg, "[]")))
    } catch (e: Exception) {
        emptyList()
    }

    /** [track] (from the app in its source) is liked, or not. */
    @Synchronized
    fun set(ctx: Context, track: Track, liked: Boolean) {
        val pkg = track.source
        if (pkg.isBlank() || track.title.isBlank()) return
        val list = list(ctx, pkg)
        val rest = list.filterNot { it.sameSongAs(track) }
        val updated = if (liked) {
            if (list.firstOrNull()?.sameSongAs(track) == true) return // already the newest
            listOf(track.copy(queueId = NO_QUEUE_ID)) + rest
        } else {
            if (rest.size == list.size) return // wasn't there
            rest
        }
        prefs(ctx).edit().putString(pkg, Track.listToJson(updated.take(MAX)).toString()).apply()
    }
}

/**
 * An app's own media library, the one Android Auto browses, when the app lets other apps in:
 * its recommendations (asked for the way the system asks for suggestions, then found by name),
 * or its liked songs (found by name, at the top or one level down, in its library).
 */
private object AppLibrary {
    private val likedWords = listOf("liked", "likes", "favourite", "favorite", "loved", "hearted", "thumbs up", "starred", "saved songs")
    private val recommendedWords = listOf("recommend", "for you", "suggest", "quick picks", "discover", "made for you", "mixed for you", "listen now", "home")
    private val libraryWords = listOf("library", "my music", "collection", "your music", "home", "browse")

    suspend fun load(ctx: Context, pkg: String, app: String, kind: String): List<Track>? = withContext(Dispatchers.Main) {
        val component = service(ctx, pkg) ?: return@withContext null
        val suggest = kind == CollectionKind.RECOMMENDED
        val hints = if (suggest) Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_SUGGESTED, true) } else null
        val browser = connect(ctx, component, hints) ?: (if (hints != null) connect(ctx, component, null) else null) ?: return@withContext null
        try {
            withTimeoutOrNull(8_000) { find(browser, suggest) }?.let { items -> items.mapNotNull { toTrack(ctx, it, pkg, app) } }
        } finally {
            runCatching { browser.disconnect() }
        }
    }

    private suspend fun find(browser: MediaBrowser, suggest: Boolean): List<MediaBrowser.MediaItem>? {
        val root = browser.root ?: return null
        val top = children(browser, root) ?: return null
        // Asked for suggestions and given them straight away.
        if (suggest && browser.extras?.getBoolean(MediaBrowserService.BrowserRoot.EXTRA_SUGGESTED) == true) {
            top.filter { it.isPlayable }.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val words = if (suggest) recommendedWords else likedWords
        val node = top.firstOrNull { it.isBrowsable && matches(it, words) } ?: run {
            // One level down, in its library or home.
            for (place in top.filter { it.isBrowsable && matches(it, libraryWords) }.take(3)) {
                val inner = children(browser, place.mediaId ?: continue) ?: continue
                inner.firstOrNull { it.isBrowsable && matches(it, words) }?.let { return@run it }
                // Its liked songs may be right there as songs.
                if (!suggest && matches(place, likedWords)) return inner.filter { it.isPlayable }
            }
            null
        } ?: return null
        val kids = children(browser, node.mediaId ?: return null) ?: return null
        val songs = kids.filter { it.isPlayable }
        if (songs.isNotEmpty()) return songs
        // A shelf of lists (e.g. a "Liked songs" playlist inside "Liked"): the best-named one.
        val list = kids.firstOrNull { it.isBrowsable && matches(it, words) } ?: kids.firstOrNull { it.isBrowsable } ?: return null
        return children(browser, list.mediaId ?: return null)?.filter { it.isPlayable }
    }

    private fun matches(item: MediaBrowser.MediaItem, words: List<String>): Boolean {
        val title = item.description.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
        return words.any { it in title }
    }

    private fun toTrack(ctx: Context, item: MediaBrowser.MediaItem, pkg: String, app: String): Track? {
        val d = item.description
        val title = d.title?.toString()?.trim().orEmpty()
        if (title.isBlank()) return null
        val artist = d.subtitle?.toString()?.trim().orEmpty()
        val album = d.description?.toString()?.trim()?.takeIf { it != artist }.orEmpty()
        val track = Track(
            title = title,
            artist = artist,
            album = album,
            artKey = TextMatch.artKey(artist, album, title),
            source = pkg,
            sourceName = app,
        )
        // Asked for by the app's own id when it's picked; its picture as its cover.
        Prefs.saveMediaRef(ctx, pkg, track.songKey, d.mediaId, d.mediaUri?.toString())
        ArtStore.offer(track.artKey, d.iconBitmap, d.iconUri?.toString())
        return track
    }

    private fun service(ctx: Context, pkg: String): ComponentName? = try {
        ctx.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    } catch (e: Exception) {
        null
    }

    /** Connected, or null if the app turned us away or didn't answer. */
    private suspend fun connect(ctx: Context, component: ComponentName, hints: Bundle?): MediaBrowser? =
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                val holder = arrayOfNulls<MediaBrowser>(1)
                val callback = object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        if (cont.isActive) cont.resumeWith(Result.success(holder[0]))
                    }

                    override fun onConnectionFailed() {
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                }
                val browser = MediaBrowser(ctx, component, callback, hints)
                holder[0] = browser
                cont.invokeOnCancellation { runCatching { browser.disconnect() } }
                browser.connect()
            }
        }

    private suspend fun children(browser: MediaBrowser, parent: String): List<MediaBrowser.MediaItem>? =
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val callback = object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                        runCatching { browser.unsubscribe(parentId, this) }
                        if (cont.isActive) cont.resumeWith(Result.success(children.toList()))
                    }

                    override fun onError(parentId: String) {
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                }
                cont.invokeOnCancellation { runCatching { browser.unsubscribe(parent, callback) } }
                browser.subscribe(parent, callback)
            }
        }
}

/**
 * Deezer's free artist radio (songs like an artist's): for the artists played in an app lately,
 * merged, to play in that app.
 */
private object DeezerRadio {
    private const val PER_ARTIST = 15

    /** Songs like the artists of [recent] (newest first), and those artists. */
    suspend fun like(ctx: Context, recent: List<Track>, pkg: String, app: String): Pair<List<Track>, List<String>> = withContext(Dispatchers.IO) {
        val artists = recent.map { it.artist }.filter { it.isNotBlank() }
            .distinctBy { TextMatch.primaryArtist(it) }
            .take(3)
        val lists = artists.map { a -> async { runCatching { radio(a, pkg, app) }.getOrDefault(emptyList()) } }.map { it.await() }
        val out = ArrayList<Track>()
        for (t in Collections.interleave(lists)) if (out.none { it.sameSongAs(t) }) out += t
        out to artists.map { it.split(',').first().trim() }
    }

    private fun radio(artist: String, pkg: String, app: String): List<Track> {
        val who = TextMatch.primaryArtist(artist)
        if (who.isBlank()) return emptyList()
        val found = Http.json("https://api.deezer.com/search/artist?q=${enc(who)}&limit=5")?.optJSONArray("data") ?: return emptyList()
        val candidates = (0 until found.length()).mapNotNull { found.optJSONObject(it) }
        val id = (
            candidates.firstOrNull { TextMatch.loose(it.optString("name")) == who }
                ?: candidates.firstOrNull { val n = TextMatch.loose(it.optString("name")); n.isNotEmpty() && (n.contains(who) || who.contains(n)) }
            )?.optLong("id")?.takeIf { it > 0 } ?: return emptyList()
        val data = Http.json("https://api.deezer.com/artist/$id/radio?limit=$PER_ARTIST")?.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim()
            val by = o.optJSONObject("artist")?.optString("name").orEmpty()
            val album = o.optJSONObject("album")?.optString("title").orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val t = Track(
                title = title,
                artist = by,
                album = album,
                artKey = TextMatch.artKey(by, album, title),
                durationMs = o.optLong("duration", 0L) * 1000L,
                source = pkg,
                sourceName = app,
            )
            o.optJSONObject("album")?.optString("cover_big")?.takeIf { it.startsWith("http") }?.let { ArtStore.offer(t.artKey, null, it) }
            t
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
