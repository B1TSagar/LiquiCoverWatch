package com.coverflow.mobile

import android.content.Context
import com.coverflow.shared.Lrc
import com.coverflow.shared.Lyrics
import com.coverflow.shared.TextMatch
import com.coverflow.shared.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Synced lyrics from LRCLIB (lrclib.net), a free, open lyrics database with no API key.
 * Results are cached on the phone; "not found" is remembered for a day.
 */
object LyricsLookup {
    private const val BASE = "https://lrclib.net/api"
    private const val NOT_FOUND_RETRY_MS = 24 * 60 * 60 * 1000L

    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun get(ctx: Context, track: Track): Lyrics = withContext(Dispatchers.IO) {
        val key = track.songKey
        val file = File(dir(ctx), "$key.json")
        if (file.exists()) {
            val cached = runCatching { Lyrics.fromBytes(file.readBytes()) }.getOrNull()
            val fresh = System.currentTimeMillis() - file.lastModified() < NOT_FOUND_RETRY_MS
            if (cached != null && (cached.found || fresh)) return@withContext cached
        }
        if (!Prefs.onlineLookup(ctx)) {
            return@withContext Lyrics(key, note = "Online lookup is off in the phone app")
        }
        val fetched = fetch(track)
        if (fetched == null) {
            // Network trouble: don't cache, try again next time.
            return@withContext Lyrics(key, note = "Couldn't reach the lyrics service")
        }
        runCatching { file.writeBytes(fetched.toBytes()) }
        fetched
    }

    /** null = network failure; a Lyrics with found=false = the service has nothing. */
    private fun fetch(track: Track): Lyrics? {
        val key = track.songKey
        val seconds = track.durationMs / 1000

        // 1. Exact match (needs all four fields).
        if (track.album.isNotBlank() && seconds > 0) {
            val url = "$BASE/get?track_name=${enc(track.title)}&artist_name=${enc(track.artist)}" +
                "&album_name=${enc(track.album)}&duration=$seconds"
            val (code, body) = Http.request(url)
            if (code == -1) return null
            if (code in 200..299 && body != null) {
                val hit = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
                if (hit != null && hasLyrics(hit)) return toLyrics(key, hit)
            }
        }

        // 2. Search by title + artist, then by a loose free-text query.
        val queries = listOf(
            "$BASE/search?track_name=${enc(track.title)}&artist_name=${enc(track.artist)}",
            "$BASE/search?q=${enc("${TextMatch.loose(track.title)} ${TextMatch.primaryArtist(track.artist)}".trim())}",
        )
        var anyResponse = false
        for (url in queries) {
            val (code, body) = Http.request(url)
            // Unreachable, rate-limited or failing: not an answer (and not cached as one).
            if (code == -1 || code == 429 || code >= 500) continue
            anyResponse = true
            if (body == null) continue
            val results = runCatching { JSONArray(String(body, Charsets.UTF_8)) }.getOrNull() ?: continue
            val best = (0 until results.length())
                .mapNotNull { results.optJSONObject(it) }
                .filter { hasLyrics(it) }
                .map { it to score(it, track) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }
                ?.first
            if (best != null) return toLyrics(key, best)
        }
        return if (anyResponse) Lyrics(key, found = false, note = "No lyrics found for this song") else null
    }

    private fun hasLyrics(o: JSONObject): Boolean =
        o.optBoolean("instrumental") ||
            o.optString("syncedLyrics").let { it.isNotBlank() && it != "null" } ||
            o.optString("plainLyrics").let { it.isNotBlank() && it != "null" }

    private fun score(o: JSONObject, track: Track): Int {
        val title = TextMatch.score(o.optString("trackName"), track.title)
        if (title < 2) return 0
        val artistWanted = TextMatch.primaryArtist(track.artist)
        val artistGot = TextMatch.loose(o.optString("artistName"))
        val artist = when {
            artistWanted.isEmpty() -> 0
            artistGot.contains(artistWanted) || artistWanted.contains(artistGot) -> 3
            else -> -4
        }
        val duration = o.optDouble("duration", 0.0)
        val durationScore = if (track.durationMs > 0 && duration > 0) {
            val diff = abs(duration - track.durationMs / 1000.0)
            when {
                diff <= 2 -> 4
                diff <= 5 -> 2
                diff <= 15 -> 0
                else -> -5
            }
        } else 0
        val synced = if (o.optString("syncedLyrics").let { it.isNotBlank() && it != "null" }) 3 else 0
        return title * 2 + artist + durationScore + synced
    }

    private fun toLyrics(key: String, o: JSONObject): Lyrics {
        val synced = o.optString("syncedLyrics").takeIf { it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it != "null" }.orEmpty()
        return Lyrics(
            songKey = key,
            synced = Lrc.parse(synced),
            plain = plain,
            instrumental = o.optBoolean("instrumental"),
            found = true,
        )
    }
}
