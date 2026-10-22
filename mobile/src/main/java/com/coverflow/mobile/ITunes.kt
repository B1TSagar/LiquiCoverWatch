package com.coverflow.mobile

import com.coverflow.shared.TextMatch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Minimal HTTP GET helper (no extra dependencies). */
object Http {
    /** Status code (-1 on network failure) and body for 2xx responses; [readMs] is how long to wait for the answer. */
    fun request(url: String, readMs: Int = 8_000): Pair<Int, ByteArray?> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return -1 to null
        }
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CoverFlowWatch/1.0 (Android; Galaxy Watch cover flow)")
            val code = conn.responseCode
            code to (if (code in 200..299) conn.inputStream.use { it.readBytes() } else null)
        } catch (e: Exception) {
            -1 to null
        } finally {
            conn.disconnect()
        }
    }

    fun get(url: String): ByteArray? = request(url).second

    /** POST [body] (JSON) with [headers]: status code (-1 on network failure) and body for 2xx responses. */
    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap(), readMs: Int = 8_000): Pair<Int, ByteArray?> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return -1 to null
        }
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = readMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            code to (if (code in 200..299) conn.inputStream.use { it.readBytes() } else null)
        } catch (e: Exception) {
            -1 to null
        } finally {
            conn.disconnect()
        }
    }

    fun json(url: String): JSONObject? = get(url)?.let {
        try {
            JSONObject(String(it, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Apple's free iTunes Search API, used for covers the player doesn't share and for
 * full album tracklists. Searches the phone's country store first, then the US store.
 */
object ITunes {
    private const val BASE = "https://itunes.apple.com"

    private fun countries(): List<String> {
        val c = Region.code
        return if (c.length == 2 && c != "US") listOf(c, "US") else listOf("US")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun search(term: String, entity: String, country: String, limit: Int = 15): List<JSONObject> {
        if (term.isBlank()) return emptyList()
        val res: JSONArray = Http.json("$BASE/search?term=${enc(term)}&entity=$entity&limit=$limit&country=$country")
            ?.optJSONArray("results") ?: return emptyList()
        return (0 until res.length()).mapNotNull { res.optJSONObject(it) }
    }

    /**
     * Finds the catalog album for a song. Tries an album search first, then a song search
     * (whose result also carries the album's collectionId and artwork).
     * The returned object gets a "_country" field with the store it was found in.
     */
    fun findAlbum(artist: String, album: String, title: String): JSONObject? {
        val who = TextMatch.primaryArtist(artist)
        for (country in countries()) {
            if (album.isNotBlank()) {
                val best = search("$who ${TextMatch.loose(album)}", "album", country)
                    .map { it to albumScore(it, artist, album) }
                    .filter { it.second >= 4 }
                    .maxWithOrNull(edition(album))?.first
                if (best != null) return best.put("_country", country)
            }
            if (title.isNotBlank()) {
                val best = search("$who ${TextMatch.loose(title)}", "song", country)
                    .map { it to songScore(it, artist, title, album) }
                    .filter { it.second >= 4 }
                    .maxWithOrNull(edition(album))?.first
                if (best != null) return best.put("_country", country)
            }
        }
        return null
    }

    /**
     * Finds the catalog song itself (with a "_country" field for the store it was found in), or
     * null. Its "trackViewUrl" is the song's page, which other services can match.
     */
    fun findSong(artist: String, title: String, album: String): JSONObject? {
        if (title.isBlank()) return null
        val who = TextMatch.primaryArtist(artist)
        for (country in countries()) {
            val best = search("$who ${TextMatch.loose(title)}".trim(), "song", country)
                .map { it to songScore(it, artist, title, album) }
                .filter { it.second >= 4 }
                .maxByOrNull { it.second }?.first
            if (best != null) return best.put("_country", country)
        }
        return null
    }

    /**
     * Among equally good matches, the right edition: the one named exactly as the player names
     * the album (the catalog often has "Album", "Album - EP" and "Album (Deluxe)", which compare
     * the same loosely), then the fullest (an EP or single is otherwise easily taken for the
     * album, showing only some of its tracks).
     */
    private fun edition(album: String): Comparator<Pair<JSONObject, Int>> =
        compareBy<Pair<JSONObject, Int>>({ it.second })
            .thenBy { if (album.isNotBlank() && it.first.optString("collectionName").trim().equals(album.trim(), ignoreCase = true)) 1 else 0 }
            .thenBy { it.first.optInt("trackCount", 0) }

    private fun albumScore(o: JSONObject, artist: String, album: String): Int =
        TextMatch.score(o.optString("collectionName"), album) * 2 +
            artistScore(o.optString("artistName"), artist)

    private fun songScore(o: JSONObject, artist: String, title: String, album: String): Int {
        val titleScore = TextMatch.score(o.optString("trackName"), title)
        if (titleScore < 2) return -10
        val albumBonus = if (album.isNotBlank() && TextMatch.score(o.optString("collectionName"), album) >= 2) 1 else 0
        return titleScore * 2 + artistScore(o.optString("artistName"), artist) + albumBonus
    }

    private fun artistScore(candidate: String, wanted: String): Int {
        if (wanted.isBlank()) return 0
        val a = TextMatch.loose(candidate)
        val b = TextMatch.primaryArtist(wanted)
        return when {
            b.isEmpty() -> 0
            a == b -> 2
            a.contains(b) || b.contains(a) -> 1
            else -> -3
        }
    }

    /** Songs by [artist] in the phone's store (then the US store), most relevant first. */
    fun songsBy(artist: String, limit: Int = 20): List<JSONObject> {
        val who = TextMatch.primaryArtist(artist)
        if (who.isBlank()) return emptyList()
        for (country in countries()) {
            val res = Http.json("$BASE/search?term=${enc(who)}&entity=song&attribute=artistTerm&limit=${limit + 10}&country=$country")
                ?.optJSONArray("results") ?: continue
            val songs = (0 until res.length()).mapNotNull { res.optJSONObject(it) }
                .filter { o -> TextMatch.loose(o.optString("artistName")).let { it == who || it.contains(who) } }
                .take(limit)
            if (songs.isNotEmpty()) return songs
        }
        return emptyList()
    }

    /** Artwork URL resized to [size] px, or null. */
    fun artworkUrl(artist: String, album: String, title: String, size: Int): String? {
        val hit = findAlbum(artist, album, title) ?: return null
        val url = hit.optString("artworkUrl100").ifBlank { hit.optString("artworkUrl60") }
        if (url.isBlank()) return null
        return url.replace(Regex("\\d+x\\d+bb"), "${size}x${size}bb")
    }

    /**
     * The album's full tracklist (album info, tracks), or null. The album is the catalog's best
     * match that has the song in it (so a single, an EP or a compilation named alike isn't taken
     * for it), and its tracks come from whichever store lists the most of them: a store only
     * lists the tracks it has, which can be fewer than the album has in the music app.
     */
    fun albumTracks(artist: String, album: String, title: String): Pair<JSONObject, List<JSONObject>>? {
        var fallback: Pair<JSONObject, List<JSONObject>>? = null
        for (hit in albumCandidates(artist, album, title)) {
            val full = fullestTracks(hit) ?: continue
            if (title.isBlank() || full.second.any { TextMatch.score(it.optString("trackName"), title) >= 2 }) return full
            if (fallback == null) fallback = full
        }
        return fallback
    }

    /** How many albums are looked into for the one with the song, at most. */
    private const val ALBUM_CANDIDATES = 4

    /**
     * Albums that could be the song's, best first: from album searches (with the artist, then
     * without), then the albums of matching songs; each with a "_country" field for its store.
     */
    private fun albumCandidates(artist: String, album: String, title: String): List<JSONObject> {
        val who = TextMatch.primaryArtist(artist)
        val out = LinkedHashMap<Long, JSONObject>()
        fun add(list: List<JSONObject>, country: String) {
            for (o in list) {
                val id = o.optLong("collectionId", -1L)
                if (id > 0 && !out.containsKey(id)) out[id] = o.put("_country", country)
            }
        }
        for (country in countries()) {
            if (album.isNotBlank()) {
                for (term in listOf("$who ${TextMatch.loose(album)}", TextMatch.loose(album)).map { it.trim() }.distinct()) {
                    add(
                        search(term, "album", country, limit = 25)
                            .map { it to albumScore(it, artist, album) }
                            .filter { it.second >= 4 }
                            .sortedWith(edition(album).reversed())
                            .map { it.first },
                        country,
                    )
                    if (out.size >= ALBUM_CANDIDATES) return out.values.take(ALBUM_CANDIDATES)
                }
            }
            if (title.isNotBlank()) {
                add(
                    search("$who ${TextMatch.loose(title)}".trim(), "song", country, limit = 25)
                        .map { it to songScore(it, artist, title, album) }
                        .filter { it.second >= 4 }
                        .sortedWith(edition(album).reversed())
                        .map { it.first },
                    country,
                )
            }
            if (out.size >= ALBUM_CANDIDATES) break
        }
        return out.values.take(ALBUM_CANDIDATES)
    }

    /**
     * [hit]'s album with its tracks, from its own store, and also from the US store when that one
     * lists fewer tracks than the album has: whichever lists more.
     */
    private fun fullestTracks(hit: JSONObject): Pair<JSONObject, List<JSONObject>>? {
        val id = hit.optLong("collectionId", -1L)
        if (id <= 0) return null
        val home = hit.optString("_country", "US")
        var best: Pair<JSONObject, List<JSONObject>>? = null
        for (country in listOf(home, "US").distinct()) {
            val got = lookupAlbum(id, country) ?: continue
            val kept = best?.takeIf { it.second.size >= got.second.size } ?: got
            best = kept
            val expected = got.first.optInt("trackCount", 0)
            if (expected > 0 && kept.second.size >= expected) break
        }
        return best?.let { (info, tracks) -> (if (info.optString("collectionName").isNotEmpty()) info else hit) to tracks }
    }

    private fun lookupAlbum(id: Long, country: String): Pair<JSONObject, List<JSONObject>>? {
        val res = Http.json("$BASE/lookup?id=$id&entity=song&limit=200&country=$country")
            ?.optJSONArray("results") ?: return null
        val all = (0 until res.length()).mapNotNull { res.optJSONObject(it) }
        val info = all.firstOrNull { it.optString("wrapperType") == "collection" } ?: JSONObject()
        val tracks = all
            .filter { it.optString("wrapperType") == "track" && it.optString("kind", "song") == "song" }
            .sortedWith(compareBy({ it.optInt("discNumber", 1) }, { it.optInt("trackNumber", 0) }))
        return if (tracks.isEmpty()) null else info to tracks
    }
}
