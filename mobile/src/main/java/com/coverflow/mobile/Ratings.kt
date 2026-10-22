package com.coverflow.mobile

import android.content.Context
import com.coverflow.shared.SongRating
import com.coverflow.shared.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Like / star / 0-5 rating per song. Kept on the phone (so they work with any player), and
 * passed on to the player app when it supports it: likes via its rating API or its "like"
 * button action, stars only if it takes 5-star ratings.
 */
object Ratings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("coverflow_ratings", Context.MODE_PRIVATE)

    private fun load(ctx: Context, key: String): SongRating {
        val raw = prefs(ctx).getString(key, null) ?: return SongRating(key)
        return runCatching { SongRating.fromJson(JSONObject(raw)).copy(songKey = key) }.getOrDefault(SongRating(key))
    }

    private fun save(ctx: Context, r: SongRating) {
        val stored = r.copy(likeInPlayer = false, starsInPlayer = false) // capabilities aren't stored
        prefs(ctx).edit().putString(r.songKey, stored.toJson().toString()).apply()
    }

    /** Our stored rating, with the player's own like/stars taking priority when it reports them. */
    suspend fun current(ctx: Context, track: Track): SongRating {
        val local = load(ctx, track.songKey)
        val player = withContext(Dispatchers.Main) { MediaHub.ratingInfo(track) }
        return local.copy(
            liked = player.liked ?: local.liked,
            stars = player.stars ?: local.stars,
            likeInPlayer = player.likeInPlayer,
            starsInPlayer = player.starsInPlayer,
        )
    }

    /**
     * Apply a rating from the watch and return the result:
     *  - 3-5 stars also likes the song (in the player too, when it supports likes),
     *  - 1-2 stars puts it back to not liked (a thumbs-down where the player has one),
     *  - 0 stars (no rating) leaves likes alone.
     */
    suspend fun apply(ctx: Context, track: Track, change: SongRating): SongRating {
        val before = current(ctx, track)
        val stars = change.stars.coerceIn(0, 5)
        val liked: Boolean? = when {
            stars >= 3 -> true
            stars in 1..2 -> false
            else -> null
        }
        val after = before.copy(
            songKey = track.songKey,
            stars = stars,
            liked = liked ?: before.liked,
        )
        save(ctx, after)
        // Liked (or not) on the watch: the watch's Liked covers for the app follow.
        if (liked != null) {
            val source = track.source.ifBlank { withContext(Dispatchers.Main) { MediaHub.lastAppPackage(ctx) }.orEmpty() }
            if (source.isNotBlank()) LikedStore.set(ctx, track.copy(source = source), liked)
        }
        withContext(Dispatchers.Main) {
            MediaHub.applyRating(
                track,
                // Always (re)assert it: the player only acts when its state differs, and our
                // stored value may not match the player.
                liked = liked,
                stars = after.stars.takeIf { it != before.stars },
            )
        }
        return after.copy(likeInPlayer = before.likeInPlayer, starsInPlayer = before.starsInPlayer)
    }
}
