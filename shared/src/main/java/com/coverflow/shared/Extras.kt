package com.coverflow.shared

import org.json.JSONArray
import org.json.JSONObject

/** Lyrics for one song, sent phone -> watch. */
data class Lyrics(
    val songKey: String,
    /** Time-synced lines; empty when only plain lyrics exist. */
    val synced: List<LyricLine> = emptyList(),
    val plain: String = "",
    val instrumental: Boolean = false,
    /** False when nothing was found (or lookup is off / offline). */
    val found: Boolean = false,
    /** Short reason shown when not found, e.g. "Online lookup is off". */
    val note: String = "",
) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("k", songKey)
        put("s", JSONArray().apply {
            synced.forEach { put(JSONObject().apply { put("t", it.timeMs); put("x", it.text) }) }
        })
        put("p", plain)
        put("i", instrumental)
        put("f", found)
        put("n", note)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): Lyrics? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val arr = o.optJSONArray("s")
            val lines = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { LyricLine(it.optLong("t"), it.optString("x")) }
            }
            Lyrics(
                songKey = o.getString("k"),
                synced = lines,
                plain = o.optString("p"),
                instrumental = o.optBoolean("i"),
                found = o.optBoolean("f"),
                note = o.optString("n"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** The user's like and 0-5 rating for a song. */
data class SongRating(
    val songKey: String,
    val liked: Boolean = false,
    val stars: Int = 0,
    /** The player app supports liking, so a like here also likes it there. */
    val likeInPlayer: Boolean = false,
    /** The player app supports 5-star ratings, so stars are sent there too. */
    val starsInPlayer: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("k", songKey)
        put("l", liked)
        put("r", stars)
        put("lp", likeInPlayer)
        put("rp", starsInPlayer)
    }

    fun toBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(o: JSONObject) = SongRating(
            songKey = o.optString("k"),
            liked = o.optBoolean("l"),
            stars = o.optInt("r").coerceIn(0, 5),
            likeInPlayer = o.optBoolean("lp"),
            starsInPlayer = o.optBoolean("rp"),
        )

        fun fromBytes(bytes: ByteArray): SongRating? = try {
            fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }
}

/** Watch -> phone: read the rating for [track], or set it to [change]. */
data class RatingRequest(val track: Track, val change: SongRating? = null) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("tr", track.toJson())
        change?.let { put("c", it.toJson()) }
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): RatingRequest? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            RatingRequest(Track.fromJson(o.getJSONObject("tr")), o.optJSONObject("c")?.let(SongRating::fromJson))
        } catch (e: Exception) {
            null
        }
    }
}
