package com.coverflow.mobile

import android.content.Context
import com.coverflow.shared.TextMatch
import com.coverflow.shared.Track
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Finds a song's video on YouTube Music, so YouTube Music can be opened straight into playing
 * it. YouTube Music plays a song link from any app, but ignores "play this song" requests that
 * other apps send through its media controls (only Google's own apps may send those), and it
 * shares no id for its songs either: so each song's video is looked up online.
 *
 * First the song is searched for on YouTube itself, the most exact way, since the titles YouTube
 * Music shows are YouTube's own: with the user's YouTube API key if one is set in the phone app,
 * otherwise through YouTube Music's own web search (unofficial, so it may stop working if
 * YouTube changes it). If that finds nothing, the song is found on a music catalog (Apple's iTunes catalog, then
 * Deezer's), and Songlink (song.link), a free service that matches songs across music apps,
 * gives its YouTube Music link. Many songs played in YouTube Music carry video titles and
 * channel names ("Full Video: Song | Film | Actors" by a record label), so catalogs are searched
 * with the title cleaned up, and without the artist if that finds nothing. Songlink can take
 * several seconds for a song it hasn't matched before and allows 10 lookups a minute, so YouTube
 * Music songs are looked up ahead of time, one at a time, while they play and while they're
 * among the recently played ([warm]); videos found are remembered, so picking one later starts
 * it at once.
 */
object YouTubeMusicLinks {
    const val PACKAGE = "com.google.android.apps.youtube.music"

    /**
     * Where found videos are kept, best first (the store of players' own song ids, under this
     * name; 4: several per song, checked to play and to be by the artist), and videos that turned out wrong for a song.
     */
    private const val FOUND = "youtube-music-link4"
    private const val BAD = "youtube-music-bad"

    private const val SONGLINK = "https://api.song.link/v1-alpha.1/links"

    /** How long to wait for Songlink's answer (ms): matching a new song can take a while. */
    private const val SONGLINK_READ_MS = 20_000

    /** Background lookups keep at least this far apart (ms): within Songlink's 10 a minute, and gentle on YouTube's search. */
    private const val BACKGROUND_GAP_MS = 7_000L

    /** After Songlink says "too many", background lookups wait this long (ms). */
    private const val BUSY_PAUSE_MS = 60_000L

    /** A song that couldn't be found isn't looked up again in the background for this long (ms). */
    private const val RETRY_AFTER_MS = 6 * 60 * 60_000L

    /** How many recently played YouTube Music songs are looked up ahead. */
    private const val WARM_MAX = 15

    /** How many matches are kept per song (the next is opened if one turns out wrong), and checked at most. */
    private const val KEEP = 3
    private const val CHECK_MAX = 6

    private val bareId = Regex("^[A-Za-z0-9_-]{11}$")
    private val idInLink = Regex("(?:[?&]v=|youtu\\.be/|/watch/|/shorts/)([A-Za-z0-9_-]{11})")

    /** The video id in [s]: a bare id, or one inside a YouTube link. */
    fun videoIn(s: String?): String? {
        if (s.isNullOrBlank()) return null
        if (bareId.matches(s)) return s
        return idInLink.find(s)?.groupValues?.get(1)
    }

    private fun idsIn(s: String?): List<String> = s.orEmpty().split(',').mapNotNull { videoIn(it.trim()) }

    /** Videos that turned out wrong for the song (never opened for it again). */
    private fun badOf(ctx: Context, track: Track): Set<String> = idsIn(Prefs.mediaRef(ctx, BAD, track.songKey)?.id).toSet()

    /**
     * The song's videos, best first, wrong ones left out: YouTube Music's own id for it, if it
     * shared one while the song played or was queued (the very video it played), then the ones
     * found online before.
     */
    fun knownAll(ctx: Context, track: Track): List<String> {
        val bad = badOf(ctx, track)
        val own = Prefs.mediaRef(ctx, PACKAGE, track.songKey)?.let { videoIn(it.id) ?: videoIn(it.uri) }
        val found = idsIn(Prefs.mediaRef(ctx, FOUND, track.songKey)?.id)
        return (listOfNotNull(own) + found).distinct().filter { it !in bad }
    }

    /** The song's best video found before, if any. */
    fun known(ctx: Context, track: Track): String? = knownAll(ctx, track).firstOrNull()

    /** What a lookup found: the song's videos, best first, or why there's none. */
    class Lookup(val videos: List<String>, val whyNot: String? = null, val detail: String = "") {
        val video: String? get() = videos.firstOrNull()
    }

    /** The link that opens YouTube Music playing [video]. */
    fun watchLink(video: String) = "https://music.youtube.com/watch?v=$video"

    // ---- Looking up -------------------------------------------------------------------------

    /** Lookups running, by song (a pick joins one already running in the background). */
    private val running = HashMap<String, Deferred<Lookup>>()

    /** When each song was last looked up and not found. */
    private val notFoundAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastRequestAt = 0L

    @Volatile
    private var busyUntil = 0L

    /** Look the song's videos up (or join the lookup already running for it). */
    fun lookUp(ctx: Context, track: Track): Deferred<Lookup> {
        val app = ctx.applicationContext
        val key = track.songKey
        synchronized(running) {
            running[key]?.takeIf { it.isActive }?.let { return it }
            val job = AppScope.async {
                try {
                    lookUpNow(app, track)
                } finally {
                    synchronized(running) { running.remove(key) }
                }
            }
            running[key] = job
            return job
        }
    }

    /** Runs on [AppScope]'s background threads (its network calls block). */
    private suspend fun lookUpNow(ctx: Context, track: Track): Lookup {
        knownAll(ctx, track).takeIf { it.isNotEmpty() }?.let { return Lookup(it, detail = "found before") }
        if (!Prefs.onlineLookup(ctx)) return Lookup(emptyList(), "online lookups are off in Cover Display on the phone")
        if (track.title.isBlank()) return Lookup(emptyList(), "the song has no title")
        val bad = badOf(ctx, track)
        var why = "the song isn't on the iTunes or Deezer catalogs, which the lookup starts from"
        var gaveUp = false
        val details = ArrayList<String>()

        // 1. YouTube itself: with the user's API key if there is one, otherwise through YouTube
        // Music's own web search (unofficial, so it may stop working if YouTube changes it). Its
        // matches, best first, each checked to play here before it's used.
        val key = Prefs.youtubeApiKey(ctx)
        lastRequestAt = System.currentTimeMillis()
        val (matches, whySearch) = if (key != null) YouTubeSearch.find(key, track) else WebSearch.find(track)
        val searchName = if (key != null) "YouTube search" else "YouTube Music search"
        val ready = playable(matches.filter { it !in bad }, track)
        details += if (matches.isEmpty()) "$searchName: $whySearch" else "$searchName: ${matches.size} found, ${ready.summary}"
        if (ready.videos.isNotEmpty()) return found(ctx, track, ready.videos, details)
        why = when {
            matches.isEmpty() -> whySearch
            matches.all { it in bad } -> "the videos found played other songs in YouTube Music before"
            else -> "the matches found can't be played in YouTube Music in ${country()}, or are other songs (${ready.summary})"
        }

        // 2. The song on iTunes, then on Deezer (as named, then with its title cleaned up and
        // without the artist); for each, its YouTube Music link from Songlink.
        val title = cleanTitle(track.title, track.artist)
        val itunes = ITunes.findSong(track.artist, title, track.album) ?: ITunes.findSong("", title, "")
        val itunesPage = itunes?.optString("trackViewUrl")?.takeIf { it.isNotBlank() }
        if (itunesPage != null) {
            val (videos, whyNot) = songlink("url=${enc(itunesPage)}&userCountry=${country()}")
            val ok = playable(videos.filter { it !in bad }, track)
            details += if (videos.isEmpty()) "iTunes + Songlink: $whyNot" else "iTunes + Songlink: ${videos.size} found, ${ok.summary}"
            if (ok.videos.isNotEmpty()) return found(ctx, track, ok.videos, details)
            if (videos.isEmpty()) why = whyNot
            gaveUp = whyNot.startsWith(BUSY) || whyNot.startsWith(UNREACHABLE)
        }
        if (!gaveUp) {
            (Deezer.findSong(track.artist, title, track.durationMs) ?: Deezer.findSong("", title, track.durationMs))?.let { id ->
                val (videos, whyNot) = songlink("platform=deezer&type=song&id=$id&userCountry=${country()}")
                val ok = playable(videos.filter { it !in bad }, track)
                details += if (videos.isEmpty()) "Deezer + Songlink: $whyNot" else "Deezer + Songlink: ${videos.size} found, ${ok.summary}"
                if (ok.videos.isNotEmpty()) return found(ctx, track, ok.videos, details)
                if (videos.isEmpty()) why = whyNot
                gaveUp = whyNot.startsWith(BUSY) || whyNot.startsWith(UNREACHABLE)
            }
        }
        if (!gaveUp) notFoundAt[track.songKey] = System.currentTimeMillis()
        return Lookup(emptyList(), why, details.joinToString("; "))
    }

    /** Matches checked with YouTube Music's player: those to use (best first), and what the check found. */
    private class Checked(val videos: List<String>, val summary: String)

    /**
     * Of [videos] (best first), those YouTube Music's player says can play here and whose own
     * title (and artist and length, where known) match the song; any it can't tell about are
     * kept. A match of quite another length goes after the rest. The player is asked about all
     * of them at once, along with a song that plays everywhere: if that one doesn't pass either,
     * the check isn't working from here (YouTube changed something, or wants a sign-in), and
     * nothing is turned down for being unplayable.
     */
    private suspend fun playable(videos: List<String>, track: Track): Checked {
        val ids = videos.distinct().take(CHECK_MAX)
        if (ids.isEmpty()) return Checked(emptyList(), "none to check")
        val (infos, trusted) = coroutineScope {
            val control = async(Dispatchers.IO) { Player.trusted() }
            val each = ids.map { id -> async(Dispatchers.IO) { Player.info(id) } }
            each.awaitAll() to control.await()
        }
        val good = ArrayList<String>()
        val later = ArrayList<String>()
        var unavailable = 0
        var other = 0
        var unknown = 0
        ids.forEachIndexed { i, id ->
            val info = infos[i]
            val playable = if (trusted) info?.playable else null
            when {
                info == null -> {
                    unknown++
                    good += id // couldn't tell: give it a go
                }
                playable == false -> unavailable++ // unavailable here (YouTube Music would play something else)
                info.title.isBlank() -> good += id
                !titleMatches(info.title, info.author, track) -> other++
                else -> {
                    val offLength = track.durationMs > 0 && info.lengthMs > 0 && abs(info.lengthMs - track.durationMs) > 30_000
                    // By someone else: only the very title, and the same length (otherwise likely a cover or another song).
                    val byOther = byArtist(info.author, track) == false
                    if (byOther && (offLength || !exactTitle(info.title, info.author, track))) {
                        other++
                    } else if (offLength) later += id else good += id
                }
            }
        }
        val kept = (good + later).take(KEEP)
        val summary = buildList {
            add("${kept.size} kept")
            if (unavailable > 0) add("$unavailable can't play in ${country()}")
            if (other > 0) add("$other other songs")
            if (unknown > 0) add("$unknown not checked")
            if (!trusted) add("playability not checkable from here")
        }.joinToString(", ")
        return Checked(kept, summary)
    }

    private fun titleMatches(title: String, author: String, track: Track): Boolean =
        TextMatch.score(title, track.title) >= 2 ||
            TextMatch.score(cleanTitle(title, author), cleanTitle(track.title, track.artist)) >= 2

    /** The very same title (as named, or once both are cleaned of video words), not just one containing the other. */
    private fun exactTitle(title: String, author: String, track: Track): Boolean {
        val a = TextMatch.loose(title)
        if (a.isNotEmpty() && a == TextMatch.loose(track.title)) return true
        val b = TextMatch.loose(cleanTitle(title, author))
        return b.isNotEmpty() && b == TextMatch.loose(cleanTitle(track.title, track.artist))
    }

    private val artistSplit = Regex("\\s*(,|&|/|;|\\bfeat\\.?|\\bft\\.?|\\bwith\\b|\\sx\\s)\\s*", RegexOption.IGNORE_CASE)

    /**
     * Whether [text] (a channel name, or a search result's subtitle) names any of the song's
     * artists, as whole words; null if the song names no artist.
     */
    private fun byArtist(text: String, track: Track): Boolean? {
        val names = track.artist.split(artistSplit).map { TextMatch.loose(it) }.filter { it.length >= 2 }
        if (names.isEmpty()) return null
        val t = " ${TextMatch.loose(text)} "
        return names.any { t.contains(" $it ") }
    }

    private val videoPrefix = Regex(
        "^\\s*(full|official|lyrical|lyric|video|audio|new)?\\s*(video|audio|song|lyrics?|lyrical)?(\\s+(video|song))?\\s*[:\\-\u2013\u2014]\\s+",
        RegexOption.IGNORE_CASE,
    )
    private val videoWords = Regex(
        "(?i)[(\\[]?\\b(official\\s+(music\\s+)?(video|audio|lyric video)|full\\s+(video|song|audio)( song)?|lyrical( video)?|video song|audio song|4k|hd)\\b[)\\]]?",
    )

    /**
     * A video title cut down to the song's name: "Full Video: Song | Film | Actors" -> "Song",
     * "Song (Official Music Video)" -> "Song", "Artist - Song" -> "Song" (with [artist] given).
     * Plain song titles stay as they are.
     */
    fun cleanTitle(title: String, artist: String = ""): String {
        var t = title.substringBefore('|').trim()
        val who = TextMatch.primaryArtist(artist)
        val dash = Regex("\\s[-\u2013\u2014]\\s").find(t)
        if (dash != null && who.isNotEmpty() && TextMatch.loose(t.substring(0, dash.range.first)).contains(who)) {
            t = t.substring(dash.range.last + 1)
        }
        val prefixed = videoPrefix.find(t)
        if (prefixed != null && prefixed.value.any { it.isLetter() }) t = t.substring(prefixed.range.last + 1)
        t = t.replace(videoWords, " ").replace(Regex("\\s{2,}"), " ").trim().trim('-', ':', ' ')
        return t.ifBlank { title }
    }

    /**
     * [video] played something else for the song (unavailable here, or another song): never
     * opened for it again; the next video found for it is used, or it's looked up again.
     */
    fun forget(ctx: Context, track: Track, video: String) {
        val bad = (badOf(ctx, track) + video).toList().takeLast(10)
        Prefs.saveMediaRef(ctx, BAD, track.songKey, bad.joinToString(","), null)
        val left = idsIn(Prefs.mediaRef(ctx, FOUND, track.songKey)?.id).filter { it !in bad }
        Prefs.saveMediaRef(ctx, FOUND, track.songKey, left.joinToString(",").ifEmpty { "-" }, null)
    }

    private fun found(ctx: Context, track: Track, videos: List<String>, details: List<String>): Lookup {
        Prefs.saveMediaRef(ctx, FOUND, track.songKey, videos.joinToString(","), null)
        notFoundAt.remove(track.songKey)
        return Lookup(videos, detail = details.joinToString("; "))
    }

    /** [video] is [track]'s video (from YouTube Music's own lists): it's opened first when the song is picked. */
    fun remember(ctx: Context, track: Track, video: String) {
        if (videoIn(video) == null) return
        val known = idsIn(Prefs.mediaRef(ctx, FOUND, track.songKey)?.id)
        Prefs.saveMediaRef(ctx, FOUND, track.songKey, (listOf(video) + known).distinct().take(KEEP).joinToString(","), null)
    }

    /** A song on YouTube Music's radio for another song. */
    class RadioSong(val video: String, val title: String, val artist: String, val album: String, val durationMs: Long, val cover: String?)

    /**
     * YouTube Music's radio for [video]: the songs it plays after it, like it (its own web API,
     * no sign-in; unofficial, so it may stop working if YouTube changes it). Without [video]
     * itself; empty if it can't be had.
     */
    fun radio(video: String): List<RadioSong> {
        val body = JSONObject()
            .put("videoId", video)
            .put("playlistId", "RDAMVM$video")
            .put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true)
            .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        val (_, root) = InnerTube.call("next", body)
        root ?: return emptyList()
        val rows = ArrayList<JSONObject>()
        collectAll(root, "playlistPanelVideoRenderer", rows)
        val out = ArrayList<RadioSong>()
        for (row in rows) {
            val id = videoIn(row.optString("videoId"))
                ?: videoIn(row.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId"))
                ?: continue
            if (id == video || out.any { it.video == id }) continue
            val title = runsText(row.optJSONObject("title"))
            if (title.isBlank()) continue
            // "Artist • Album • 2022" for songs, "Artist • 1.2B views • 4:30" for videos.
            val byline = runsText(row.optJSONObject("longBylineText")).ifBlank { runsText(row.optJSONObject("shortBylineText")) }
            val parts = byline.split(" • ").map { it.trim() }
            val artist = parts.getOrNull(0).orEmpty()
            val album = parts.getOrNull(1)?.takeIf { parts.size >= 3 && !it.contains("view", ignoreCase = true) && !Regex("^\\d{4}$").matches(it) }.orEmpty()
            val length = Regex("^(\\d+):(\\d{2})$").find(runsText(row.optJSONObject("lengthText")).trim())
            val ms = length?.let { (it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong()) * 1000 } ?: 0L
            val thumbs = row.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val cover = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.startsWith("http") }
            out += RadioSong(id, title, artist, album, ms, cover)
        }
        return out
    }

    /** Every object under [key] in [node], in order. */
    private fun collectAll(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out += v else collectAll(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectAll(node.opt(i), key, out)
        }
    }

    /** The text of a "runs" block, all its pieces together. */
    private fun runsText(block: JSONObject?): String {
        val runs = block?.optJSONArray("runs") ?: return block?.optString("simpleText").orEmpty()
        return buildString { for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty()) }
    }

    private const val BUSY = "Songlink is busy"
    private const val UNREACHABLE = "Songlink couldn't be reached"

    /** Songlink's YouTube Music (then YouTube) videos for a song, or why there are none. */
    private fun songlink(query: String): Pair<List<String>, String> {
        val (code, body) = Http.request("$SONGLINK?$query", readMs = SONGLINK_READ_MS)
        lastRequestAt = System.currentTimeMillis()
        if (code == 429) {
            busyUntil = System.currentTimeMillis() + BUSY_PAUSE_MS
            return emptyList<String>() to "$BUSY (it allows 10 lookups a minute); try again in a minute"
        }
        if (code < 0) return emptyList<String>() to "$UNREACHABLE (no connection, or it took too long)"
        if (body == null) return emptyList<String>() to "Songlink couldn't match the song"
        val links = try {
            JSONObject(String(body, Charsets.UTF_8)).optJSONObject("linksByPlatform")
        } catch (e: Exception) {
            null
        } ?: return emptyList<String>() to "Songlink couldn't match the song"
        val videos = listOf("youtubeMusic", "youtube")
            .mapNotNull { videoIn(links.optJSONObject(it)?.optString("url")) }
            .distinct()
        return videos to (if (videos.isEmpty()) "Songlink has no YouTube Music link for it" else "")
    }

    // ---- Looking up ahead of time -----------------------------------------------------------

    private val queued = LinkedHashMap<String, Track>()
    private var worker: Job? = null

    /**
     * Look these YouTube Music songs up in the background (the playing one, and the recently
     * played, newest first), one at a time, unless already found or recently not found.
     */
    fun warm(ctx: Context, tracks: List<Track>) {
        if (tracks.isEmpty() || !Prefs.onlineLookup(ctx)) return
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        synchronized(queued) {
            for (t in tracks.take(WARM_MAX)) {
                val key = t.songKey
                if (t.title.isBlank() || queued.containsKey(key) || known(app, t) != null) continue
                if (now - (notFoundAt[key] ?: 0L) < RETRY_AFTER_MS) continue
                queued[key] = t
            }
            if (queued.isNotEmpty() && worker?.isActive != true) worker = AppScope.launch { drain(app) }
        }
    }

    private suspend fun drain(ctx: Context) {
        while (true) {
            val next = synchronized(queued) { queued.values.firstOrNull() } ?: return
            // Leave room for picks within Songlink's limit, and back off when it's busy.
            val wait = maxOf(lastRequestAt + BACKGROUND_GAP_MS, busyUntil) - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            runCatching { lookUp(ctx, next).await() }
            synchronized(queued) { queued.remove(next.songKey) }
        }
    }

    private fun country(): String = Region.code

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * YouTube's own search (the YouTube Data API, with the user's key): the video whose title
     * matches the song's, which for songs played in YouTube Music is the very title it showed.
     */
    private object YouTubeSearch {
        /** Its matches for the song, best first, or why there are none. */
        fun find(key: String, track: Track): Pair<List<String>, String> {
            val q = "${track.title} ${TextMatch.primaryArtist(track.artist)}".trim()
            val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=8" +
                "&regionCode=${country()}&q=${enc(q)}&key=${enc(key)}"
            val (code, body) = Http.request(url)
            if (code == 400 || code == 403) return emptyList<String>() to "YouTube refused the search (check the YouTube API key in Cover Display on the phone, or its daily limit)"
            if (body == null) return emptyList<String>() to "YouTube's search couldn't be reached"
            val items = try {
                JSONObject(String(body, Charsets.UTF_8)).optJSONArray("items")
            } catch (e: Exception) {
                null
            } ?: return emptyList<String>() to "YouTube's search found nothing"
            val scored = ArrayList<Pair<String, Int>>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optJSONObject("id")?.optString("videoId")?.takeIf { videoIn(it) != null } ?: continue
                val snippet = item.optJSONObject("snippet") ?: continue
                val title = unescape(snippet.optString("title"))
                val titleScore = maxOf(TextMatch.score(title, track.title), TextMatch.score(cleanTitle(title), cleanTitle(track.title, track.artist)))
                if (titleScore < 1 || (titleScore < 2 && i > 1)) continue
                val by = byArtist(unescape(snippet.optString("channelTitle")), track)
                // By someone else: only with the very title (the player checks its length next).
                if (by == false && !exactTitle(title, "", track)) continue
                val artistScore = when (by) {
                    null -> 0
                    true -> 2
                    false -> -2
                }
                // YouTube's own order counts too: earlier results are likelier.
                scored += id to (titleScore * 3 + artistScore - i)
            }
            val ranked = scored.sortedByDescending { it.second }.map { it.first }.distinct()
            return ranked to "YouTube's search found no video with the song's title"
        }

        private fun unescape(s: String) = s
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
    }

    /**
     * YouTube Music's own search, the one its website uses (no key; unofficial, so it may stop
     * working if YouTube changes it): the song or video whose title matches, by the same artist
     * (or channel) and of the same length where it can tell.
     */
    private object WebSearch {
        private val notPlayable = listOf("Album", "Single", "EP", "Artist", "Playlist", "Profile", "Podcast", "Episode", "Station")
        private val length = Regex("\\b(\\d{1,2}):(\\d{2})\\b")

        /** Its matches for the song, best first, or why there are none. */
        fun find(track: Track): Pair<List<String>, String> {
            val q = "${track.title} ${TextMatch.primaryArtist(track.artist)}".trim()
            val (code, root) = InnerTube.call("search", JSONObject().put("query", q))
            if (code < 0) return emptyList<String>() to "YouTube Music's search couldn't be reached"
            if (root == null) return emptyList<String>() to "YouTube Music's search didn't answer ($code); a YouTube API key in Cover Display on the phone is the dependable way"
            val items = ArrayList<JSONObject>()
            collect(root, items)
            val scored = ArrayList<Pair<String, Int>>()
            items.forEachIndexed { i, item ->
                val id = videoIdIn(item) ?: return@forEachIndexed
                val title = textOf(item.optJSONArray("flexColumns")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
                    .ifBlank { textOf(item.optJSONObject("title")) }
                val subtitle = textOf(item.optJSONArray("flexColumns")?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
                    .ifBlank { textOf(item.optJSONObject("subtitle")) }
                if (title.isBlank()) return@forEachIndexed
                // Songs and videos only (an album or playlist named like the song could otherwise win).
                if (notPlayable.any { subtitle.startsWith(it, ignoreCase = true) }) return@forEachIndexed
                val titleScore = maxOf(
                    TextMatch.score(title, track.title),
                    TextMatch.score(cleanTitle(title), cleanTitle(track.title, track.artist)),
                )
                // A loose title match only counts for its top results.
                if (titleScore < 1 || (titleScore < 2 && i > 1)) return@forEachIndexed
                val by = byArtist(subtitle, track)
                val artistScore = when (by) {
                    null -> 0
                    true -> 2
                    false -> -2
                }
                val lengthScore = length.findAll(subtitle).lastOrNull()?.let { m ->
                    val ms = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000
                    if (track.durationMs > 0) (if (abs(ms - track.durationMs) <= 5_000) 2 else -1) else 0
                } ?: 0
                val sameTitle = exactTitle(title, "", track)
                // By someone else: only with the very title and no other length (a song merely
                // named like it, such as "Song Reprise" by another artist, is another song).
                if (by == false && (!sameTitle || lengthScore < 0)) return@forEachIndexed
                val exact = if (sameTitle) 2 else 0
                // Its songs (the audio tracks) play most widely; then its order counts too.
                val song = if (subtitle.startsWith("Song", ignoreCase = true)) 1 else 0
                scored += id to (titleScore * 3 + exact + artistScore + lengthScore + song - i)
            }
            val ranked = scored.sortedByDescending { it.second }.map { it.first }.distinct()
            return ranked to "YouTube Music's search found nothing with the song's title"
        }

        /** Every result in the answer, in order: list rows and the top result's card. */
        private fun collect(node: Any?, out: MutableList<JSONObject>) {
            when (node) {
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = node.opt(k)
                        if ((k == "musicResponsiveListItemRenderer" || k == "musicCardShelfRenderer") && v is JSONObject) out += v
                        collect(v, out)
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
            }
        }

        /** The video a result plays: its own (rows), or the one its title or play button opens. */
        private fun videoIdIn(item: JSONObject): String? {
            item.optJSONObject("playlistItemData")?.optString("videoId")?.let { videoIn(it) }?.let { return it }
            return firstWatchVideo(item.optJSONObject("title") ?: item.optJSONArray("flexColumns")?.optJSONObject(0))
                ?: firstWatchVideo(item.optJSONObject("overlay"))
                ?: firstWatchVideo(item.optJSONObject("thumbnailOverlay"))
        }

        private fun firstWatchVideo(node: Any?): String? {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("watchEndpoint")?.optString("videoId")?.let { videoIn(it) }?.let { return it }
                    val keys = node.keys()
                    while (keys.hasNext()) firstWatchVideo(node.opt(keys.next()))?.let { return it }
                }
                is JSONArray -> for (i in 0 until node.length()) firstWatchVideo(node.opt(i))?.let { return it }
            }
            return null
        }

        /** The text of a "runs" block, all its pieces together. */
        private fun textOf(block: JSONObject?): String {
            val runs = block?.optJSONArray("runs") ?: return ""
            return buildString { for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty()) }
        }
    }

    /** YouTube Music's own web API (what its website uses; unofficial, so it may change). */
    private object InnerTube {
        /**
         * The web client's version, dated a couple of days back as its own builds are (a fixed
         * one gets too old for YouTube in time, and its answers then stop being useful).
         */
        private fun clientVersion(): String {
            val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return "1." + day.format(Date(System.currentTimeMillis() - 2 * 24 * 60 * 60_000L)) + ".01.00"
        }

        /** POST [body] (with the web client's context added) to [endpoint]: status code (-1 if unreachable) and answer. */
        fun call(endpoint: String, body: JSONObject): Pair<Int, JSONObject?> {
            val version = clientVersion()
            body.put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", version)
                        .put("hl", "en")
                        .put("gl", country()),
                ),
            )
            val (code, bytes) = Http.postJson(
                "https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false",
                body.toString(),
                mapOf(
                    "Origin" to "https://music.youtube.com",
                    "Referer" to "https://music.youtube.com/",
                    "X-YouTube-Client-Name" to "67",
                    "X-YouTube-Client-Version" to version,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36",
                ),
            )
            val json = bytes?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
            return code to json
        }
    }

    /** What YouTube Music's player says about a video: whether it can play here, and what it is. */
    private object Player {
        class Info(val playable: Boolean?, val title: String, val author: String, val lengthMs: Long)

        /** A song that plays everywhere ("Never Gonna Give You Up"), to see whether the check itself works from here. */
        private const val CONTROL = "dQw4w9WgXcQ"
        private const val TRUST_MS = 60 * 60_000L
        private const val DISTRUST_MS = 10 * 60_000L

        @Volatile
        private var trustedUntil = 0L

        @Volatile
        private var distrustedUntil = 0L

        /** Whether the player's word on a video counts: the control song passes the check (asked at most every few minutes). */
        fun trusted(): Boolean {
            val now = System.currentTimeMillis()
            if (now < trustedUntil) return true
            if (now < distrustedUntil) return false
            val ok = info(CONTROL)?.playable == true
            if (ok) trustedUntil = now + TRUST_MS else distrustedUntil = now + DISTRUST_MS
            return ok
        }

        /** Reasons that are about the asking (an old client, a bot check), not the video. */
        private val notAboutVideo = listOf("this app", "latest version", "not a bot", "sign in to confirm you", "unusual traffic", "try again later")

        /** The video's info, or null if the player couldn't be asked. */
        fun info(video: String): Info? {
            val (_, root) = InnerTube.call("player", JSONObject().put("videoId", video))
            root ?: return null
            val status = root.optJSONObject("playabilityStatus")
            val state = status?.optString("status").orEmpty()
            val reason = status?.optString("reason").orEmpty().lowercase(Locale.ROOT)
            val playable = when {
                state == "OK" -> true
                // Age-restricted songs play in the app once signed in.
                "age" in reason -> true
                notAboutVideo.any { it in reason } -> null
                state == "UNPLAYABLE" || state == "ERROR" -> false
                else -> null
            }
            val details = root.optJSONObject("videoDetails")
            return Info(
                playable = playable,
                title = details?.optString("title").orEmpty(),
                author = details?.optString("author").orEmpty(),
                lengthMs = (details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L) * 1000L,
            )
        }
    }

    /** Deezer's free catalog search: a second place to find the song when iTunes doesn't have it. */
    private object Deezer {
        /** The Deezer song id for the best match, or null. */
        fun findSong(artist: String, title: String, durationMs: Long): Long? {
            val who = TextMatch.primaryArtist(artist)
            val name = TextMatch.loose(title)
            if (name.isBlank()) return null
            val queries = listOfNotNull(
                if (who.isNotEmpty()) "artist:\"$who\" track:\"$name\"" else null,
                "$who $name".trim(),
            ).distinct()
            for (q in queries) {
                val data = Http.json("https://api.deezer.com/search?q=${enc(q)}&limit=10")?.optJSONArray("data") ?: continue
                val best = (0 until data.length())
                    .mapNotNull { data.optJSONObject(it) }
                    .map { it to score(it, artist, title, durationMs) }
                    .filter { it.second >= 4 }
                    .maxByOrNull { it.second }?.first
                if (best != null) return best.optLong("id").takeIf { it > 0 }
            }
            return null
        }

        private fun score(o: JSONObject, artist: String, title: String, durationMs: Long): Int {
            val titleScore = TextMatch.score(o.optString("title"), title)
            if (titleScore < 2) return -10
            val name = o.optJSONObject("artist")?.optString("name").orEmpty()
            val a = TextMatch.loose(name)
            val b = TextMatch.primaryArtist(artist)
            val artistScore = when {
                b.isEmpty() -> 0
                a == b -> 2
                a.contains(b) || b.contains(a) -> 1
                else -> -3
            }
            // Same length (within 15 s): likely the same recording.
            val seconds = o.optLong("duration", 0L)
            val lengthScore = if (durationMs > 0 && seconds > 0) (if (abs(seconds * 1000 - durationMs) <= 15_000) 1 else -1) else 0
            return titleScore * 2 + artistScore + lengthScore
        }
    }
}
