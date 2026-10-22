package com.coverflow.mobile

import android.content.Context
import android.content.SharedPreferences
import com.coverflow.shared.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray

/** Process-wide background scope (the notification listener keeps the process alive). */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

object Prefs {
    private const val ONLINE_LOOKUP = "online_lookup"
    private const val HISTORY = "history"
    const val HISTORY_MAX = 40

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("coverflow", Context.MODE_PRIVATE)

    /** Look up missing covers and full album tracklists on the iTunes catalog. */
    fun onlineLookup(ctx: Context): Boolean = prefs(ctx).getBoolean(ONLINE_LOOKUP, true)

    fun setOnlineLookup(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(ONLINE_LOOKUP, enabled).apply()
    }

    // ---- Player apps ------------------------------------------------------------------------

    private const val EXCLUDED = "excluded_players"
    private const val SEEN = "seen_players"

    /** Package names of player apps the user switched off; their sessions are ignored. */
    fun excludedPlayers(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(EXCLUDED, null)?.toSet() ?: emptySet()

    fun setPlayerExcluded(ctx: Context, pkg: String, excluded: Boolean) {
        val set = excludedPlayers(ctx).toMutableSet()
        if (excluded) set.add(pkg) else set.remove(pkg)
        prefs(ctx).edit().putStringSet(EXCLUDED, set).apply()
    }

    /** Every app we've seen with a media session, so it can be excluded even when idle. */
    fun seenPlayers(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(SEEN, null)?.toSet() ?: emptySet()

    fun addSeenPlayers(ctx: Context, packages: Collection<String>) {
        val set = seenPlayers(ctx)
        if (set.containsAll(packages)) return
        prefs(ctx).edit().putStringSet(SEEN, set + packages).apply()
    }

    /** The app that played music most recently (used for songs with no recorded source). */
    fun lastPlayer(ctx: Context): String? = prefs(ctx).getString("last_player", null)

    fun setLastPlayer(ctx: Context, pkg: String) {
        if (lastPlayer(ctx) != pkg) prefs(ctx).edit().putString("last_player", pkg).apply()
    }

    /**
     * The user's own YouTube Data API key (optional): songs picked for YouTube Music are then
     * searched for on YouTube itself, the most exact way to find their videos.
     */
    fun youtubeApiKey(ctx: Context): String? = prefs(ctx).getString("youtube_api_key", null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setYoutubeApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString("youtube_api_key", key.trim()).apply()
    }

    /** Whether [pkg] passed over a request by its own song id last time (then a search goes first). */
    fun ignoresIds(ctx: Context, pkg: String): Boolean = prefs(ctx).getBoolean("ignores_ids_$pkg", false)

    fun setIgnoresIds(ctx: Context, pkg: String, ignores: Boolean) {
        if (ignoresIds(ctx, pkg) != ignores) prefs(ctx).edit().putBoolean("ignores_ids_$pkg", ignores).apply()
    }

    /** The steps of the last song picked on the watch (shown on the phone screen). */
    fun lastTry(ctx: Context): List<String> =
        prefs(ctx).getString("last_try", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun setLastTry(ctx: Context, steps: List<String>) {
        prefs(ctx).edit().putString("last_try", steps.joinToString("\n") { it.replace('\n', ' ') }).apply()
    }

    /**
     * How a picked song last got playing in [pkg]: "session" (the app took the request from its
     * media controls) or "open" (it had to be opened with the request). null = not known yet.
     */
    fun playMethod(ctx: Context, pkg: String): String? = prefs(ctx).getString("play_method2_$pkg", null)

    fun setPlayMethod(ctx: Context, pkg: String, method: String) {
        if (playMethod(ctx, pkg) != method) prefs(ctx).edit().putString("play_method2_$pkg", method).apply()
    }

    /**
     * Which request [pkg]'s media controls last started a picked song with ("search", "id" or
     * "uri"), so it's asked that way first next time. null = not known yet.
     */
    fun playRequest(ctx: Context, pkg: String): String? = prefs(ctx).getString("play_request_$pkg", null)

    fun setPlayRequest(ctx: Context, pkg: String, kind: String) {
        if (playRequest(ctx, pkg) != kind) prefs(ctx).edit().putString("play_request_$pkg", kind).apply()
    }

    // ---- Like buttons -----------------------------------------------------------------------

    /** The button the user picked as "like" for a player app, or null for automatic. */
    fun likeButton(ctx: Context, pkg: String): String? = prefs(ctx).getString("like_button_$pkg", null)

    fun setLikeButton(ctx: Context, pkg: String, key: String?) {
        val editor = prefs(ctx).edit()
        if (key == null) editor.remove("like_button_$pkg") else editor.putString("like_button_$pkg", key)
        editor.apply()
    }

    /**
     * For like buttons whose label doesn't say whether the song is already liked: remember the
     * songs we pressed it for, so we never press it twice (which would un-like).
     * Returns true the first time for a song.
     */
    fun markPressedLike(ctx: Context, songKey: String): Boolean {
        val set = prefs(ctx).getStringSet("liked_by_button", null)?.toSet() ?: emptySet()
        if (songKey in set) return false
        prefs(ctx).edit().putStringSet("liked_by_button", (set + songKey).toList().takeLast(2_000).toSet()).apply()
        return true
    }

    /** Forget that we liked [songKey] by button. Returns true if we had. */
    fun unmarkPressedLike(ctx: Context, songKey: String): Boolean {
        val set = prefs(ctx).getStringSet("liked_by_button", null)?.toSet() ?: return false
        if (songKey !in set) return false
        prefs(ctx).edit().putStringSet("liked_by_button", set - songKey).apply()
        return true
    }

    /** Recently played songs, oldest first. Used when the player doesn't share its queue. */
    fun history(ctx: Context): List<Track> = try {
        Track.listFromJson(JSONArray(prefs(ctx).getString(HISTORY, "[]")))
    } catch (e: Exception) {
        emptyList()
    }

    fun saveHistory(ctx: Context, list: List<Track>) {
        prefs(ctx).edit().putString(HISTORY, Track.listToJson(list.takeLast(HISTORY_MAX)).toString()).apply()
    }

    // ---- The player's own ids for songs ---------------------------------------------------

    /**
     * How a player app itself names a song it played (its media id, e.g. "spotify:track:…", and
     * media URI, when it shares them), so the song can be asked for exactly later on.
     */
    class MediaRef(val id: String?, val uri: String?)

    private const val MEDIA_REFS = "media_refs"
    private const val MEDIA_REFS_MAX = 600
    private const val LINKS = "youtube-music-"

    /** "pkg|songKey" -> ref, oldest first; loaded once. */
    private var mediaRefs: LinkedHashMap<String, MediaRef>? = null

    private fun refs(ctx: Context): LinkedHashMap<String, MediaRef> {
        mediaRefs?.let { return it }
        val map = LinkedHashMap<String, MediaRef>()
        try {
            val a = JSONArray(prefs(ctx).getString(MEDIA_REFS, "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                map[o.optString("k")] = MediaRef(o.optString("i").ifEmpty { null }, o.optString("u").ifEmpty { null })
            }
        } catch (e: Exception) {
            // Damaged: start again.
        }
        mediaRefs = map
        return map
    }

    @Synchronized
    fun mediaRef(ctx: Context, pkg: String, songKey: String): MediaRef? = refs(ctx)["$pkg|$songKey"]

    @Synchronized
    fun saveMediaRef(ctx: Context, pkg: String, songKey: String, id: String?, uri: String?) =
        saveMediaRefs(ctx, pkg, listOf(Triple(songKey, id, uri)))

    /** Remember several songs' refs at once (e.g. a whole queue), writing once. */
    @Synchronized
    fun saveMediaRefs(ctx: Context, pkg: String, songs: List<Triple<String, String?, String?>>) {
        val map = refs(ctx)
        var changed = false
        for ((songKey, id, uri) in songs) {
            if (id.isNullOrBlank() && uri.isNullOrBlank()) continue
            val key = "$pkg|$songKey"
            val old = map[key]
            if (old != null && old.id == id && old.uri == uri) continue
            map.remove(key)
            map[key] = MediaRef(id?.ifBlank { null }, uri?.ifBlank { null })
            changed = true
        }
        if (!changed) return
        // Players' refs and YouTube Music's found and bad links (YouTubeMusicLinks' "youtube-music-…"
        // entries) are capped apart: a long queue mustn't push out links that took a lookup to find.
        for (links in listOf(false, true)) {
            val keys = map.keys.filter { it.startsWith(LINKS) == links }
            keys.take((keys.size - MEDIA_REFS_MAX).coerceAtLeast(0)).forEach { map.remove(it) }
        }
        val a = JSONArray()
        for ((k, r) in map) {
            a.put(org.json.JSONObject().apply {
                put("k", k)
                r.id?.let { put("i", it) }
                r.uri?.let { put("u", it) }
            })
        }
        prefs(ctx).edit().putString(MEDIA_REFS, a.toString()).apply()
    }
}
