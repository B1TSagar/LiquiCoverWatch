package com.coverflow.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wear Data Layer paths shared by the phone and watch apps.
 *
 * Phone -> watch:
 *  - [STATE]        DataItem holding the latest [PlayerState] as JSON (persisted, so the
 *                   watch has something to show the moment it opens).
 *  - [COVER_PREFIX] Message "<prefix><artKey>" whose payload is a [CoverPacket]: the cover
 *                   ready to show (sized for the watch), its line drawing and its colour.
 *  - [ALBUM]        Message with an [AlbumResponse].
 *
 * Watch -> phone:
 *  - [CMD]          Message with a [Command].
 *  - [REQ_ART]      Message with an [ArtRequest] (one cover, answered straight away).
 *  - [REQ_COVERS]   Message with a [CoverBatchRequest] (many covers, nearest first).
 *  - [REQ_ALBUM]    Message with an [AlbumRequest].
 */
object Paths {
    const val STATE = "/coverflow/state"
    const val CMD = "/coverflow/cmd"
    const val REQ_ART = "/coverflow/req/art"
    const val REQ_COVERS = "/coverflow/req/covers"
    const val COVER_PREFIX = "/coverflow/cover/"

    /** Message "<prefix><artKey>" (no payload): that cover turned out to be wrong, forget it. */
    const val ART_DROP_PREFIX = "/coverflow/artdrop/"
    const val REQ_ALBUM = "/coverflow/req/album"
    const val ALBUM = "/coverflow/album"

    /** Watch -> phone: a [Track] (JSON). Phone -> watch: [Lyrics]. */
    const val REQ_LYRICS = "/coverflow/req/lyrics"
    const val LYRICS = "/coverflow/lyrics"

    /** Watch -> phone: a [RatingRequest] (read, or change). Phone -> watch: [SongRating]. */
    const val REQ_RATING = "/coverflow/req/rating"
    const val RATING = "/coverflow/rating"

    /**
     * Phone -> watch: a picked song couldn't be started (UTF-8 text to show, saying what would
     * help).
     */
    const val PLAY_PROBLEM = "/coverflow/play_problem"

    /**
     * Watch -> phone: the watch's clock ("<ms>"). Phone -> watch: "<watch ms>,<phone ms when it
     * arrived>,<phone ms when answered>". Song positions are stamped with the phone's clock, so
     * the watch works out how far its own clock is from it (the lyrics follow the song exactly).
     */
    const val CLOCK = "/coverflow/clock"

    /**
     * Watch -> phone: a [CollectionRequest] (recommended or liked songs from an app). Phone ->
     * watch: a [CollectionResponse].
     */
    const val REQ_COLLECTION = "/coverflow/req/collection"
    const val COLLECTION = "/coverflow/collection"

    /**
     * Watch -> phone: a watch face shows the playing song (UTF-8 art key). Phone -> watch:
     * "<prefix><artKey>", a [FaceArtPacket] (the face's blurred background and colour, made on
     * the phone so the watch only shows them); sent again when a sharper copy of that cover
     * turns up.
     */
    const val REQ_FACE_ART = "/coverflow/req/face"
    const val FACE_ART_PREFIX = "/coverflow/face/"

    /**
     * Phone -> watch, to watches whose face shows the album art: "<prefix><artKey>", the
     * [FaceArtPacket] of the next song in the queue, sent a few seconds into the song before it.
     * The watch keeps it aside, ready (decoded), and puts it up the moment that song starts; the
     * phone doesn't send it again then.
     */
    const val FACE_NEXT_PREFIX = "/coverflow/facenext/"

    /**
     * Phone -> watch, to watches whose face shows the album art: what the face needs of the
     * [PlayerState] ([PlayerState.forFace], JSON), as a message the moment it changes (a new song,
     * play, pause, a skip or a seek). It gets there before the [STATE] item (which is synced, so
     * slower), so the face switches to a new song's cover, and back to its own background on a
     * pause, as soon as it can. Each state carries when it was made, so an older one arriving
     * after a newer is left alone.
     */
    const val FACE_STATE = "/coverflow/facestate"

    /** Declared by the phone app in res/values/wear.xml so the watch can find it. */
    const val PHONE_CAPABILITY = "coverflow_phone"
}

const val NO_QUEUE_ID = -1L

data class Track(
    val title: String,
    val artist: String = "",
    val album: String = "",
    /** Stable key for this track's cover (same album -> same key). */
    val artKey: String = "",
    /** Queue item id in the player's queue, or [NO_QUEUE_ID]. */
    val queueId: Long = NO_QUEUE_ID,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    /** Package of the app that played it (e.g. "com.spotify.music"), so it can be replayed there. */
    val source: String = "",
    /** That app's name, for display. */
    val sourceName: String = "",
) {
    /** Stable per-song key (artist + title), used for lyrics and ratings. */
    val songKey: String
        get() = TextMatch.artKey(artist, "", title)

    fun toBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    /** Identity used for list keys and keeping a selection while lists refresh. */
    val id: String
        get() = if (queueId != NO_QUEUE_ID) "q$queueId"
        else "s:${TextMatch.loose(title)}|${TextMatch.primaryArtist(artist)}"

    fun sameSongAs(other: Track?): Boolean {
        if (other == null) return false
        if (queueId != NO_QUEUE_ID && queueId == other.queueId) return true
        val a = TextMatch.loose(title)
        val b = TextMatch.loose(other.title)
        if (a.isEmpty() || a != b) return false
        val x = TextMatch.primaryArtist(artist)
        val y = TextMatch.primaryArtist(other.artist)
        return x.isEmpty() || y.isEmpty() || x == y || x.contains(y) || y.contains(x)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("t", title)
        put("ar", artist)
        put("al", album)
        put("k", artKey)
        if (queueId != NO_QUEUE_ID) put("q", queueId)
        if (durationMs > 0) put("d", durationMs)
        if (trackNumber > 0) put("n", trackNumber)
        if (source.isNotEmpty()) put("src", source)
        if (sourceName.isNotEmpty()) put("srcn", sourceName)
    }

    companion object {
        fun fromJson(o: JSONObject) = Track(
            title = o.optString("t"),
            artist = o.optString("ar"),
            album = o.optString("al"),
            artKey = o.optString("k"),
            queueId = o.optLong("q", NO_QUEUE_ID),
            durationMs = o.optLong("d", 0L),
            trackNumber = o.optInt("n", 0),
            source = o.optString("src"),
            sourceName = o.optString("srcn"),
        )

        fun fromBytes(bytes: ByteArray): Track? = try {
            fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (e: Exception) {
            null
        }

        fun listToJson(list: List<Track>): JSONArray =
            JSONArray().apply { list.forEach { put(it.toJson()) } }

        fun listFromJson(a: JSONArray?): List<Track> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::fromJson) }
        }
    }
}

/** Everything the watch needs to draw its screens, published by the phone. */
data class PlayerState(
    /** Player app name, e.g. "Spotify". */
    val player: String = "",
    /** Player app package, e.g. "com.spotify.music". */
    val playerPackage: String = "",
    val current: Track? = null,
    val isPlaying: Boolean = false,
    /** Playback position at wall-clock time [capturedAt]. */
    val positionMs: Long = 0L,
    val capturedAt: Long = 0L,
    val speed: Float = 1f,
    /** The song list shown as covers: the player's queue, or recently played songs. */
    val list: List<Track> = emptyList(),
    /** Index of [current] inside [list], or -1. */
    val currentIndex: Int = -1,
    val listIsQueue: Boolean = false,
    val volume: Int = 0,
    val maxVolume: Int = 0,
    /**
     * The recently played songs (oldest first), sent only while [list] is the player's queue
     * (otherwise [list] already is them): the watch can switch its covers to them.
     */
    val recent: List<Track> = emptyList(),
    /** When the player last stopped playing (wall-clock ms), or 0 while it plays. */
    val pausedAt: Long = 0L,
    /**
     * The player isn't playing just now but the music hasn't stopped: it's loading the song or
     * skipping to another (buffering, connecting, skipping, or winding). Not [isPlaying].
     */
    val between: Boolean = false,
    /**
     * When the phone made this state, on a clock that only goes forward (ms since the phone
     * started; 0 from an older phone app): a watch that hears of a state twice ([Paths.FACE_STATE],
     * then the synced item) can tell an older one arriving late from a newer one, whatever the
     * phone's wall clock does.
     */
    val seq: Long = 0L,
    /**
     * The key [current]'s face background was sent ahead under, as the queue's next song (its
     * queue entry often has no album, so it's not the key the song plays under), or blank: the
     * watch puts that one up as the song starts.
     */
    val aheadKey: String = "",
) {
    /** Playing, or between songs ([between]): the music hasn't been paused or stopped. */
    val inPlay: Boolean
        get() = isPlaying || between

    /**
     * Only what a watch face needs ([Paths.FACE_STATE]): the song's name, artist, cover key and
     * length, whether it plays, and where it is. (The name and artist go too: the face's date
     * pill shows them. Without them a new song's name was blank until the synced state came, and
     * the pill went back to the date for that moment.)
     */
    fun forFace(): PlayerState = PlayerState(
        current = current?.let { Track(title = it.title, artist = it.artist, artKey = it.artKey, durationMs = it.durationMs) },
        isPlaying = isPlaying,
        positionMs = positionMs,
        capturedAt = capturedAt,
        speed = speed,
        between = between,
        seq = seq,
        aheadKey = aheadKey,
    )

    /** The recently played songs, oldest first (the newest is the playing or paused one, if any). */
    val recentlyPlayed: List<Track>
        get() = if (listIsQueue) recent else list

    fun positionAt(nowMs: Long): Long {
        val base = if (isPlaying) positionMs + ((nowMs - capturedAt) * speed).toLong() else positionMs
        val duration = current?.durationMs ?: 0L
        return if (duration > 0) base.coerceIn(0L, duration) else base.coerceAtLeast(0L)
    }

    fun toJsonString(): String = JSONObject().apply {
        put("p", player)
        put("pk", playerPackage)
        current?.let { put("c", it.toJson()) }
        put("pl", isPlaying)
        put("pos", positionMs)
        put("at", capturedAt)
        put("sp", speed.toDouble())
        put("l", Track.listToJson(list))
        put("ci", currentIndex)
        put("q", listIsQueue)
        put("v", volume)
        put("vm", maxVolume)
        if (recent.isNotEmpty()) put("rl", Track.listToJson(recent))
        if (pausedAt > 0) put("pa", pausedAt)
        if (between) put("bw", true)
        if (seq > 0) put("sq", seq)
        if (aheadKey.isNotEmpty()) put("ak", aheadKey)
    }.toString()

    companion object {
        fun fromJsonString(s: String?): PlayerState? {
            if (s.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(s)
                PlayerState(
                    player = o.optString("p"),
                    playerPackage = o.optString("pk"),
                    current = o.optJSONObject("c")?.let(Track::fromJson),
                    isPlaying = o.optBoolean("pl"),
                    positionMs = o.optLong("pos"),
                    capturedAt = o.optLong("at"),
                    speed = o.optDouble("sp", 1.0).toFloat(),
                    list = Track.listFromJson(o.optJSONArray("l")),
                    currentIndex = o.optInt("ci", -1),
                    listIsQueue = o.optBoolean("q"),
                    volume = o.optInt("v"),
                    maxVolume = o.optInt("vm"),
                    recent = Track.listFromJson(o.optJSONArray("rl")),
                    pausedAt = o.optLong("pa", 0L),
                    between = o.optBoolean("bw"),
                    seq = o.optLong("sq", 0L),
                    aheadKey = o.optString("ak"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** A control action sent from the watch to the phone. */
data class Command(
    val action: String,
    val track: Track? = null,
    val delta: Int = 0,
    /** Target playback position for [SEEK]. */
    val positionMs: Long = 0L,
) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("a", action)
        track?.let { put("tr", it.toJson()) }
        if (delta != 0) put("dv", delta)
        if (positionMs > 0) put("pos", positionMs)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val TOGGLE = "toggle"
        const val NEXT = "next"
        const val PREV = "prev"
        const val PLAY_TRACK = "play_track"
        const val VOLUME = "volume"
        const val SEEK = "seek"
        const val SYNC = "sync"

        fun fromBytes(bytes: ByteArray): Command? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            Command(
                action = o.getString("a"),
                track = o.optJSONObject("tr")?.let(Track::fromJson),
                delta = o.optInt("dv", 0),
                positionMs = o.optLong("pos", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Watch asks for the cover of [key]. [haveSide] is the edge (px) of the original of the copy the
 * watch already has, 0 if none: the phone only answers when it has something sharper, and
 * otherwise looks for a sharper copy and pushes it when found. [size] is the edge the watch
 * draws covers at (0 = the phone's last known size); the phone sends covers cut to that size.
 */
data class ArtRequest(val key: String, val haveSide: Int = 0, val size: Int = 0) {
    fun toBytes(): ByteArray = "$key\n$haveSide\n$size".toByteArray(Charsets.UTF_8)

    companion object {
        /**
         * Covers at least this big (px) are sharp on the watch. Smaller ones are usually the
         * music app's list thumbnails, and both apps try to replace them with a bigger copy.
         */
        const val SHARP_SIDE = 300

        fun fromBytes(data: ByteArray): ArtRequest {
            val parts = String(data, Charsets.UTF_8).split('\n')
            return ArtRequest(
                parts[0].trim(),
                parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
                parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0,
            )
        }
    }
}

/** Watch asks for the tracks of the album a cover belongs to. */
data class AlbumRequest(val cover: Track) {
    val key: String get() = keyFor(cover)

    fun toBytes(): ByteArray = JSONObject().apply {
        put("k", key)
        put("cover", cover.toJson())
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun keyFor(cover: Track): String = cover.artKey.ifBlank { cover.id }

        fun fromBytes(bytes: ByteArray): AlbumRequest? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            AlbumRequest(Track.fromJson(o.getJSONObject("cover")))
        } catch (e: Exception) {
            null
        }
    }
}

data class AlbumResponse(
    /** Echo of [AlbumRequest.key]. */
    val key: String,
    val album: String,
    val artist: String,
    val tracks: List<Track>,
    /** True when the full tracklist came from the online catalog. */
    val fromCatalog: Boolean,
) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("k", key)
        put("al", album)
        put("ar", artist)
        put("tr", Track.listToJson(tracks))
        put("cat", fromCatalog)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): AlbumResponse? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            AlbumResponse(
                key = o.getString("k"),
                album = o.optString("al"),
                artist = o.optString("ar"),
                tracks = Track.listFromJson(o.optJSONArray("tr")),
                fromCatalog = o.optBoolean("cat"),
            )
        } catch (e: Exception) {
            null
        }
    }
}


/** The song lists besides the player's own: songs recommended by an app, and songs liked in it. */
object CollectionKind {
    const val RECOMMENDED = "recommended"
    const val LIKED = "liked"
}

/** Watch asks for a [CollectionKind] list from the app [pkg] (blank: the app that played last). */
data class CollectionRequest(val kind: String, val pkg: String = "") {
    fun toBytes(): ByteArray = JSONObject().put("kind", kind).put("pkg", pkg).toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): CollectionRequest? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            CollectionRequest(o.getString("kind"), o.optString("pkg"))
        } catch (e: Exception) {
            null
        }
    }
}

data class CollectionResponse(
    val kind: String,
    /** The app the songs are from (and play in), and its name. */
    val pkg: String,
    val app: String,
    val tracks: List<Track>,
    /** Where the songs came from, or why there are none, in a line. */
    val note: String = "",
) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("kind", kind)
        put("pkg", pkg)
        put("app", app)
        put("tr", Track.listToJson(tracks))
        put("note", note)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): CollectionResponse? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            CollectionResponse(
                kind = o.getString("kind"),
                pkg = o.optString("pkg"),
                app = o.optString("app"),
                tracks = Track.listFromJson(o.optJSONArray("tr")),
                note = o.optString("note"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
