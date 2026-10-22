package com.coverflow.wear

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coverflow.shared.AlbumRequest
import com.coverflow.shared.AlbumResponse
import com.coverflow.shared.ArtRequest
import com.coverflow.shared.CollectionRequest
import com.coverflow.shared.CollectionResponse
import com.coverflow.shared.Command
import com.coverflow.shared.CoverBatchRequest
import com.coverflow.shared.CoverPacket
import com.coverflow.shared.Lyrics
import com.coverflow.shared.Paths
import com.coverflow.shared.PlayerState
import com.coverflow.shared.RatingRequest
import com.coverflow.shared.SongRating
import com.coverflow.shared.TextMatch
import com.coverflow.shared.Track
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Connection { Searching, Connected, NoPhoneApp, NoPhone }

/**
 * The watch side of the Data Layer: receives the phone's [PlayerState], fetches covers and album
 * tracklists on demand, and sends playback commands.
 */
class PhoneLink(app: Application) : AndroidViewModel(app),
    DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener,
    CapabilityClient.OnCapabilityChangedListener {

    private val dataClient = Wearable.getDataClient(app)
    private val messageClient = Wearable.getMessageClient(app)
    private val capabilityClient = Wearable.getCapabilityClient(app)
    private val nodeClient = Wearable.getNodeClient(app)
    /**
     * Covers live in app storage (not the cache folder, which the system may clear), so they're
     * there instantly every time, exactly as the phone prepared them ([CoverPacket]: the picture
     * at the size it's shown and its colour). Small ones (list thumbnails)
     * are swapped for sharper copies as the phone finds them. Older builds kept plain JPEGs;
     * those are cleared out.
     */
    private val artDir = File(app.filesDir, ART_DIR).also { it.mkdirs() }
    private val oldArtDirs = listOf(File(app.cacheDir, "art"), File(app.cacheDir, "covers"), File(app.filesDir, "covers"))

    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state.asStateFlow()

    /** App name while a picked song is being started with nothing playing ("Spotify"), else null. */
    private val _starting = MutableStateFlow<String?>(null)
    val starting: StateFlow<String?> = _starting.asStateFlow()
    /** The song being started while [starting] (its cover goes behind the message). */
    private val _startingTrack = MutableStateFlow<Track?>(null)
    val startingTrack: StateFlow<Track?> = _startingTrack.asStateFlow()

    /** A short message from the phone to show for a few seconds (e.g. why a song didn't start). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    fun dismissNotice() {
        noticeJob?.cancel()
        _notice.value = null
    }
    private var startingJob: Job? = null

    private val _connection = MutableStateFlow(Connection.Searching)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    /** Decoded covers by art key. Compose reads this directly. */
    val art = mutableStateMapOf<String, ImageBitmap>()

    /** Each cover's dominant colour, for the album-tinted backgrounds. */
    val artColors = mutableStateMapOf<String, Color>()

    /** Album tracklists by [AlbumRequest.key]. */
    val albums = mutableStateMapOf<String, AlbumResponse>()

    /** Lyrics and ratings by [Track.songKey]. */
    val lyrics = mutableStateMapOf<String, Lyrics>()

    /**
     * Recommended and liked songs from the app that played last, by [CollectionKind]: the copy
     * kept from last time at once, then the phone's fresh list.
     */
    val collections = mutableStateMapOf<String, CollectionResponse>()

    /** The kinds the phone is still looking up. */
    val collectionsLoading = mutableStateMapOf<String, Boolean>()
    private val collectionJobs = HashMap<String, Job>()
    val ratings = mutableStateMapOf<String, SongRating>()
    private val lyricsRequestedAt = HashMap<String, Long>()
    private val ratingJobs = HashMap<String, Job>()

    private val artOrder = ArrayDeque<String>()

    /** Each cover's real edge in px (before it was scaled for memory). */
    private val artSides = HashMap<String, Int>()

    /** Small covers we've already asked the phone to sharpen this session. */
    private val sharpenAsked = HashSet<String>()

    /**
     * Covers are kept a little bigger than the center cover is drawn, so it's always scaled
     * down (crisp), never up. The phone cuts them to this size before sending.
     */
    private val coverMemoryPx = (app.resources.displayMetrics.widthPixels * Tuning.COVER_SIZE * Tuning.COVER_MEMORY_SCALE)
        .roundToInt().coerceIn(160, 480)

    /** Covers of the list on screen; never evicted from memory while it's shown. */
    private var pinned: Set<String> = emptySet()
    private var prefetchJob: Job? = null
    private val artRequestedAt = HashMap<String, Long>()
    private val albumRequestedAt = HashMap<String, Long>()

    @Volatile
    private var phoneNode: String? = null

    init {
        dataClient.addListener(this)
        messageClient.addListener(this)
        capabilityClient.addListener(this, Paths.PHONE_CAPABILITY)
        viewModelScope.launch {
            loadLastState()
            findPhone()
            sync()
            trimArtCache()
        }
    }

    override fun onCleared() {
        dataClient.removeListener(this)
        messageClient.removeListener(this)
        capabilityClient.removeListener(this, Paths.PHONE_CAPABILITY)
    }

    // ---- Incoming ---------------------------------------------------------------------------

    private suspend fun loadLastState() {
        try {
            val items = dataClient.dataItems.await()
            try {
                for (item in items) if (item.uri.path == Paths.STATE) applyStateItem(item)
            } finally {
                items.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No cached state", e)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == Paths.STATE) {
                applyStateItem(event.dataItem)
            }
        }
    }

    private fun applyStateItem(item: DataItem) {
        val json = DataMapItem.fromDataItem(item).dataMap.getString("json")
        PlayerState.fromJsonString(json)?.let { phone ->
            // Its times are stamped with the phone's clock: restamp them in the watch's.
            val s = restamp(phone, clockOffset)
            _state.value = s
            if (s.current != null) {
                _starting.value = null
                _startingTrack.value = null
            }
            // The picked song started after all, after the phone had given up on it: the
            // "didn't start" message no longer applies.
            val picked = lastPicked
            if (pickNotice && _notice.value != null && s.isPlaying && picked != null && samePick(s.current, picked)) {
                pickNotice = false
                dismissNotice()
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data
        when {
            path.startsWith(Paths.COVER_PREFIX) -> {
                val key = path.removePrefix(Paths.COVER_PREFIX)
                // Fresh from the phone, which keeps looking for a sharper copy on its own.
                sharpenAsked.add(key)
                viewModelScope.launch {
                    val cover = withContext(Dispatchers.IO) {
                        decode(data)?.also { runCatching { File(artDir, "$key$EXT").writeBytes(data) } }
                    }
                    if (cover != null) putArt(key, cover)
                }
            }

            path.startsWith(Paths.ART_DROP_PREFIX) -> dropArt(path.removePrefix(Paths.ART_DROP_PREFIX))

            path == Paths.ALBUM -> gotAlbum(data)

            path == Paths.COLLECTION -> CollectionResponse.fromBytes(data)?.let { got ->
                collections[got.kind] = got
                collectionsLoading[got.kind] = false
                collectionJobs.remove(got.kind)?.cancel()
                viewModelScope.launch(Dispatchers.IO) { runCatching { collectionFile(got.kind).writeBytes(data) } }
            }

            path == Paths.LYRICS -> Lyrics.fromBytes(data)?.let { lyrics[it.songKey] = it }

            path == Paths.CLOCK -> onClockReply(String(data, Charsets.UTF_8))

            // The phone couldn't start a picked song: stop waiting and say why.
            path == Paths.PLAY_PROBLEM -> {
                startingJob?.cancel()
                _starting.value = null
                _startingTrack.value = null
                pickNotice = true
                _notice.value = String(data, Charsets.UTF_8)
                noticeJob?.cancel()
                noticeJob = viewModelScope.launch {
                    delay(NOTICE_MS)
                    _notice.value = null
                }
            }

            // Ignore the phone's answer while a newer change of ours is still waiting to be sent.
            path == Paths.RATING -> SongRating.fromBytes(data)?.let { r ->
                if (ratingJobs[r.songKey]?.isActive != true) ratings[r.songKey] = r
            }
        }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        viewModelScope.launch {
            if (useCapability(capabilityInfo) != null) sync()
        }
    }

    // ---- Phone discovery --------------------------------------------------------------------

    private suspend fun findPhone(): String? = try {
        useCapability(capabilityClient.getCapability(Paths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await())
    } catch (e: CancellationException) {
        throw e // (the asking job was cancelled: says nothing about the phone)
    } catch (e: Exception) {
        _connection.value = Connection.NoPhone
        null
    }

    private suspend fun useCapability(info: CapabilityInfo): String? {
        val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
        phoneNode = node?.id
        _connection.value = if (node != null) {
            Connection.Connected
        } else {
            val anyPhone = try {
                nodeClient.connectedNodes.await().isNotEmpty()
            } catch (e: Exception) {
                false
            }
            if (anyPhone) Connection.NoPhoneApp else Connection.NoPhone
        }
        return phoneNode
    }

    private suspend fun send(path: String, data: ByteArray): Boolean {
        val node = phoneNode ?: findPhone() ?: return false
        return try {
            messageClient.sendMessage(node, path, data).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            phoneNode = null
            false
        }
    }

    // ---- Requests ---------------------------------------------------------------------------

    fun sync() {
        viewModelScope.launch { send(Paths.CMD, Command(Command.SYNC).toBytes()) }
        syncClock()
    }

    // ---- The phone's clock ------------------------------------------------------------------

    /**
     * How far ahead of the watch's clock the phone's is (ms). Song positions come stamped with
     * the phone's clock; the two clocks can be a little apart, which would show as the lyrics
     * running late (or early).
     */
    @Volatile
    private var clockOffset = 0L

    /** The quickest round trip of the current measurement (the quickest is the most exact). */
    private var bestRoundTrip = Long.MAX_VALUE
    private var clockJob: Job? = null

    /** Measure [clockOffset]: a few quick round trips to the phone, keeping the quickest. */
    private fun syncClock() {
        if (clockJob?.isActive == true) return
        clockJob = viewModelScope.launch {
            bestRoundTrip = Long.MAX_VALUE
            repeat(CLOCK_PINGS) {
                send(Paths.CLOCK, System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8))
                delay(CLOCK_PING_GAP_MS)
            }
        }
    }

    private fun onClockReply(text: String) {
        val back = System.currentTimeMillis()
        val parts = text.split(',').mapNotNull { it.trim().toLongOrNull() }
        if (parts.size < 3) return
        val (sent, arrived, answered) = parts
        val roundTrip = (back - sent) - (answered - arrived)
        if (roundTrip < 0 || roundTrip > MAX_ROUND_TRIP_MS || roundTrip >= bestRoundTrip) return
        bestRoundTrip = roundTrip
        // Halfway through the trip, the phone's clock read between [arrived] and [answered].
        val offset = ((arrived - sent) + (answered - back)) / 2
        val change = offset - clockOffset
        clockOffset = offset
        if (change != 0L) {
            _state.value?.let { s -> _state.value = restamp(s, change) }
        }
    }

    /** [s] with its phone-clock times moved [by] ms back (into the watch's clock). */
    private fun restamp(s: PlayerState, by: Long): PlayerState = s.copy(
        capturedAt = if (s.capturedAt > 0L) s.capturedAt - by else 0L,
        pausedAt = if (s.pausedAt > 0L) s.pausedAt - by else 0L,
    )

    fun requestArt(key: String) {
        if (key.isBlank()) return
        recentAsks.remove(key)
        recentAsks.addLast(key)
        if (recentAsks.size > Tuning.COVERS_KEPT_HIDDEN) recentAsks.removeFirst()
        if (art.containsKey(key)) return
        val now = SystemClock.elapsedRealtime()
        val last = artRequestedAt[key]
        if (last != null && now - last < 20_000) return
        artRequestedAt[key] = now
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { readDisk(key) }
            if (cached != null) {
                putArt(key, cached)
                askSharper(key)
            } else {
                send(Paths.REQ_ART, ArtRequest(key, 0, coverMemoryPx).toBytes())
            }
        }
    }

    /**
     * Load every cover of [list] into memory, nearest to [center] first: from storage where we
     * have it, then from the phone, in one request it answers nearest-first (along with any
     * thumbnails worth swapping for a sharper copy). Fast bezel spins then never show a
     * placeholder.
     */
    fun prefetchArt(list: List<Track>, center: Int) {
        prefetched = list to center
        val keys = list.indices
            .sortedBy { abs(it - center) }
            .map { list[it].artKey }
            .filter { it.isNotBlank() }
            .distinct()
        pinned = keys.toSet()
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            val missing = ArrayList<String>()
            for (key in keys) {
                if (art.containsKey(key)) continue
                val decoded = withContext(Dispatchers.IO) { readDisk(key) }
                if (decoded != null) putArt(key, decoded) else missing += key
            }
            val items = ArrayList<Pair<String, Int>>()
            val now = SystemClock.elapsedRealtime()
            for (key in missing) {
                if (art.containsKey(key)) continue
                val last = artRequestedAt[key]
                if (last != null && now - last < 20_000) continue
                artRequestedAt[key] = now
                items += key to 0
            }
            // Thumbnails from storage: ask whether the phone has found sharper copies since.
            for (key in keys) {
                val side = artSides[key] ?: continue
                if (side < ArtRequest.SHARP_SIDE && sharpenAsked.add(key)) items += key to side
            }
            for (chunk in items.chunked(BATCH)) {
                send(Paths.REQ_COVERS, CoverBatchRequest(coverMemoryPx, chunk).toBytes())
            }
        }
    }

    /** The list [prefetchArt] last loaded, and the cover it centred on. */
    private var prefetched: Pair<List<Track>, Int>? = null

    /** The covers the screens asked for lately: those on screen and next to them. */
    private val recentAsks = ArrayDeque<String>()

    /**
     * The app went out of sight (the watch face is back): only the covers on screen and next to
     * them, and the playing song's, stay decoded; the rest reload from storage once it's back
     * ([shown]). Kept, they held tens of MB the watch face needed just then, and LiquiWatch's
     * companion keeps this app bound, so the watch couldn't reclaim them by itself (the face
     * stalled for seconds after leaving the app).
     */
    fun hidden() {
        prefetchJob?.cancel()
        val keep = recentAsks.toSet() + setOfNotNull(_state.value?.current?.artKey)
        for (key in art.keys.toList()) {
            if (key in keep) continue
            art.remove(key)
            artSides.remove(key)
            artOrder.remove(key)
            artRequestedAt.remove(key)
        }
    }

    /** Back in sight: the list's covers loaded again, nearest first. */
    fun shown() {
        prefetched?.let { (list, center) -> prefetchArt(list, center) }
    }

    /**
     * The album's track list: shown at once from the copy kept on the watch, and asked for afresh
     * every time the album is opened (the phone looks it up again and keeps the fullest list).
     */
    fun requestAlbum(cover: Track) {
        val key = AlbumRequest.keyFor(cover)
        if (!albums.containsKey(key)) {
            viewModelScope.launch {
                val kept = withContext(Dispatchers.IO) { readAlbum(key) }
                if (kept != null && !albums.containsKey(key)) albums[key] = kept
            }
        }
        val now = SystemClock.elapsedRealtime()
        val last = albumRequestedAt[key]
        if (last != null && now - last < 2_000) return
        albumRequestedAt[key] = now
        viewModelScope.launch { send(Paths.REQ_ALBUM, AlbumRequest(cover).toBytes()) }
    }

    /**
     * Ask the phone for the [CollectionKind] songs of the app that played last (shown from the
     * copy kept on the watch meanwhile).
     */
    fun requestCollection(kind: String) {
        if (!collections.containsKey(kind)) {
            viewModelScope.launch {
                val kept = withContext(Dispatchers.IO) {
                    runCatching { collectionFile(kind).takeIf { it.exists() }?.readBytes()?.let { CollectionResponse.fromBytes(it) } }.getOrNull()
                }
                if (kept != null && !collections.containsKey(kind)) collections[kind] = kept
            }
        }
        collectionsLoading[kind] = true
        collectionJobs.remove(kind)?.cancel()
        collectionJobs[kind] = viewModelScope.launch {
            send(Paths.REQ_COLLECTION, CollectionRequest(kind, lastApp()).toBytes())
            delay(COLLECTION_WAIT_MS) // no answer: stop showing it's on its way
            collectionsLoading[kind] = false
        }
    }

    /** The app playing or paused now, else the one that played last ("" lets the phone decide). */
    private fun lastApp(): String {
        val s = _state.value ?: return ""
        return s.current?.source?.takeIf { it.isNotBlank() }
            ?: s.playerPackage.takeIf { it.isNotBlank() }
            ?: s.recentlyPlayed.lastOrNull()?.source.orEmpty()
    }

    private val collectionDir = File(app.filesDir, "collections_v1").also { it.mkdirs() }

    private fun collectionFile(kind: String) = File(collectionDir, "$kind.json")

    /** Album track lists kept on the watch (full ones, from the phone), by album key. */
    private val albumDir = File(app.filesDir, "albums_v1").also { it.mkdirs() }

    private fun albumFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return File(albumDir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun readAlbum(key: String): AlbumResponse? = try {
        albumFile(key).takeIf { it.exists() }?.readBytes()?.let { AlbumResponse.fromBytes(it) }
    } catch (e: Exception) {
        null
    }

    /** An album's list arrived: show it, and keep it if it's the full one (or the only one there is). */
    private fun gotAlbum(data: ByteArray) {
        val album = AlbumResponse.fromBytes(data) ?: return
        val shown = albums[album.key]
        // A partial list (the phone couldn't reach the catalog) doesn't replace a full one.
        if (album.fromCatalog || shown == null || !shown.fromCatalog) albums[album.key] = album
        if (album.fromCatalog) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    albumFile(album.key).writeBytes(data)
                    val files = albumDir.listFiles()
                    if (files != null && files.size > ALBUMS_KEPT) {
                        files.sortedBy { it.lastModified() }.take(files.size - ALBUMS_KEPT).forEach { it.delete() }
                    }
                }
            }
        }
    }

    fun requestLyrics(track: Track) {
        val key = track.songKey
        val cached = lyrics[key]
        if (cached != null && cached.found) return
        val now = SystemClock.elapsedRealtime()
        val last = lyricsRequestedAt[key]
        if (last != null && now - last < 15_000) return
        lyricsRequestedAt[key] = now
        viewModelScope.launch { send(Paths.REQ_LYRICS, track.toBytes()) }
    }

    /** Ask the phone for the current like/star/rating (the player may have changed it). */
    fun requestRating(track: Track) {
        viewModelScope.launch { send(Paths.REQ_RATING, RatingRequest(track).toBytes()) }
    }

    /** Show the change immediately; send it once the bezel settles. */
    fun setRating(track: Track, rating: SongRating) {
        val key = track.songKey
        ratings[key] = rating
        ratingJobs[key]?.cancel()
        ratingJobs[key] = viewModelScope.launch {
            delay(450)
            send(Paths.REQ_RATING, RatingRequest(track, rating).toBytes())
            ratingJobs.remove(key)
        }
    }

    // ---- Commands ---------------------------------------------------------------------------

    fun togglePlay() {
        _state.value?.let { s ->
            val now = System.currentTimeMillis()
            _state.value = s.copy(isPlaying = !s.isPlaying, positionMs = s.positionAt(now), capturedAt = now)
        }
        command(Command(Command.TOGGLE))
    }

    /** Jump the phone's player to [positionMs] (used when a scrub is confirmed). */
    fun seekTo(positionMs: Long) {
        _state.value?.let { s ->
            _state.value = s.copy(positionMs = positionMs, capturedAt = System.currentTimeMillis())
        }
        command(Command(Command.SEEK, positionMs = positionMs))
    }

    fun next() = command(Command(Command.NEXT))
    fun previous() = command(Command(Command.PREV))
    /** The song last picked, and whether the message showing is about it not starting. */
    private var lastPicked: Track? = null
    private var pickNotice = false

    /** Whether [current] is the picked song (as the player names it: the same song, or one title within the other). */
    private fun samePick(current: Track?, picked: Track): Boolean {
        if (current == null) return false
        if (current.sameSongAs(picked)) return true
        val a = TextMatch.loose(current.title)
        val b = TextMatch.loose(picked.title)
        return a.length >= 3 && b.length >= 3 && (a.contains(b) || b.contains(a))
    }

    fun play(track: Track) {
        lastPicked = track
        pickNotice = false
        if (_state.value?.current == null) {
            // Nothing is playing: the phone has to start the app first, which takes a moment.
            _starting.value = track.sourceName.ifBlank { "your music app" }
            _startingTrack.value = track
            startingJob?.cancel()
            startingJob = viewModelScope.launch {
                // The phone tries for up to about 50 seconds, then watches a little longer in
                // case the app is still starting the song.
                delay(80_000)
                _starting.value = null
                _startingTrack.value = null
            }
        }
        command(Command(Command.PLAY_TRACK, track = track))
    }

    fun changeVolume(delta: Int) {
        _state.value?.let { s ->
            if (s.maxVolume > 0) _state.value = s.copy(volume = (s.volume + delta).coerceIn(0, s.maxVolume))
        }
        command(Command(Command.VOLUME, delta = delta))
    }

    private fun command(c: Command) {
        viewModelScope.launch { send(Paths.CMD, c.toBytes()) }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private class DecodedCover(val image: ImageBitmap, val color: Color, val side: Int)

    /**
     * A cover as the phone prepared it: the picture is already the size it's shown at, so it's
     * only decoded (full colour, so gradients don't band); its colour comes with it.
     */
    private fun decode(bytes: ByteArray): DecodedCover? {
        val packet = CoverPacket.fromBytes(bytes) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(packet.image, 0, packet.image.size) ?: return null
        bitmap.prepareToDraw() // upload to the GPU now, not on the first frame it's shown
        return DecodedCover(bitmap.asImageBitmap(), Color(packet.color), packet.sourceSide)
    }

    /**
     * If the cover for [key] is a small thumbnail, ask the phone (once per session) for a
     * sharper copy. Returns true if it asked.
     */
    private fun askSharper(key: String): Boolean {
        val side = artSides[key] ?: return false
        if (side >= ArtRequest.SHARP_SIDE || !sharpenAsked.add(key)) return false
        viewModelScope.launch { send(Paths.REQ_ART, ArtRequest(key, side, coverMemoryPx).toBytes()) }
        return true
    }

    /** A cover from storage, marking it recently used. */
    private fun readDisk(key: String): DecodedCover? {
        val f = File(artDir, "$key$EXT")
        if (!f.exists()) return null
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        return runCatching { decode(f.readBytes()) }.getOrNull()
    }

    /** Keep decoded covers bounded; evicted ones reload from the disk cache when needed again. */
    private fun putArt(key: String, cover: DecodedCover) {
        art[key] = cover.image
        artColors[key] = cover.color
        artSides[key] = cover.side
        artOrder.remove(key)
        artOrder.addLast(key)
        while (artOrder.size > Tuning.COVERS_IN_MEMORY) {
            val old = artOrder.firstOrNull { it !in pinned } ?: break // everything left is on screen
            artOrder.remove(old)
            art.remove(old)
            artColors.remove(old)
            artSides.remove(old)
            artRequestedAt.remove(old)
        }
    }

    /** The phone found that this cover was a placeholder: forget it and fetch the replacement. */
    private fun dropArt(key: String) {
        art.remove(key)
        artColors.remove(key)
        artSides.remove(key)
        sharpenAsked.remove(key)
        artOrder.remove(key)
        artRequestedAt.remove(key)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { File(artDir, "$key$EXT").delete() } }
            requestArt(key)
        }
    }

    private suspend fun trimArtCache() = withContext(Dispatchers.IO) {
        oldArtDirs.forEach { if (it.exists()) runCatching { it.deleteRecursively() } }
        val files = artDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@withContext
        files.drop(Tuning.COVERS_ON_DISK).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "CoverFlowLink"
        private const val EXT = ".cover"
        private const val ART_DIR = "covers_v2"

        /**
         * Where the cover for [key] is kept, as the phone sent it (a [CoverPacket]). The watch
         * face background (com.coverflow.wear.face) reads and fills the same store.
         */
        fun coverFile(context: Context, key: String): File = File(File(context.filesDir, ART_DIR), "$key$EXT")

        /** Covers per batch request (keeps each message small). */
        private const val BATCH = 120

        /** How long a notice from the phone stays up (ms). */
        private const val NOTICE_MS = 12_000L

        /** How long the phone may take to find recommended or liked songs (ms). */
        private const val COLLECTION_WAIT_MS = 50_000L

        /** How many albums' track lists are kept on the watch. */
        private const val ALBUMS_KEPT = 200

        /** Round trips per clock check, how far apart (ms), and the slowest one still used (ms). */
        private const val CLOCK_PINGS = 5
        private const val CLOCK_PING_GAP_MS = 400L
        private const val MAX_ROUND_TRIP_MS = 3_000L
    }
}
