package com.coverflow.wear.face

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * What's playing, for the LiquiWatch companion on the same watch: the song's name and artist,
 * whether it plays, and its cover, round (made on the phone at the size the face shows it: see
 * [FaceArt.roundArt]), so the LiquiWatch · Expedition face's date pill can show them and open Now
 * Playing when tapped (see [com.coverflow.wear.MainActivity]). The face can't read another
 * complication's text, so the companion's own complications carry them. With them goes when this
 * app's own work for the face with a new song will be done ([FaceUpdates.busyUntil]): the
 * companion sends the song's name and cover to the face just after, not in the same beat.
 *
 * Sent only when they change: as a song starts (or the music skips to the next), and when a pause
 * has lasted as long as the face takes to go back to its own background ([FaceArt.PAUSE_GRACE_MS]),
 * so a skip doesn't flash the pill to the date and back. The companion asks for them again when
 * it starts afresh ([NowPlayingRequestReceiver]).
 *
 * On Wear OS 5 and later each app tells the other who it is with the broadcast (shared identity),
 * and only takes the other's.
 */
object NowPlayingLink {
    private const val TAG = "NowPlayingLink"
    private const val PREFS = "now_playing_link"
    private const val KEY_SENT = "sent"

    const val COMPANION = "com.liquiwatch.companion"
    const val ACTION_NOW_PLAYING = "com.liquiwatch.action.NOW_PLAYING"
    const val ACTION_REQUEST = "com.coverflow.action.NOW_PLAYING_REQUEST"
    const val EXTRA_PLAYING = "playing"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_ENDS_AT = "ends_at"
    const val EXTRA_AT = "at"
    const val EXTRA_ART_KEY = "art_key"
    const val EXTRA_ART = "art"
    const val EXTRA_BUSY_UNTIL = "busy_until"
    const val EXTRA_QUIET_UNTIL = "quiet_until"

    /**
     * Tells the companion what's playing if that's changed since it was last told ([force]: even
     * if not, as when it asks).
     */
    fun update(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val now = FaceArt.now(app)
        // (Playing as the face shows it: a pause counts once it has lasted, as for the background.)
        val playing = FaceArt.showsArt(now)
        val (title, artist) = FaceArt.track(app)
        val key = now.artKey.orEmpty()
        val art = if (playing && key.isNotEmpty()) FaceArt.roundArt(app, key) else null
        val shown = if (playing && title.isNotBlank()) "1\n$title\n$artist\n$key\n${art?.contentHashCode() ?: 0}" else "0"
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && p.getString(KEY_SENT, null) == shown) return
        p.edit().putString(KEY_SENT, shown).apply()
        val intent = Intent(ACTION_NOW_PLAYING)
            .setPackage(COMPANION)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(EXTRA_PLAYING, playing && title.isNotBlank())
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_ARTIST, artist)
            .putExtra(EXTRA_ENDS_AT, FaceArt.song(app)?.endsAt ?: 0L)
            .putExtra(EXTRA_AT, System.currentTimeMillis())
            .putExtra(EXTRA_ART_KEY, key)
            .putExtra(EXTRA_BUSY_UNTIL, FaceUpdates.busyUntil())
            .putExtra(EXTRA_QUIET_UNTIL, FaceUpdates.settlingUntil())
        if (art != null) intent.putExtra(EXTRA_ART, art)
        try {
            send(app, intent)
            Log.i(TAG, "Told the companion: ${if (playing && title.isNotBlank()) "\"$title\"${if (art != null) ", with its round cover" else ""}" else "nothing plays"}")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't tell the companion what's playing", e)
        }
    }

    /** Sends [intent], with this app's identity where the watch can share it. */
    fun send(ctx: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val options = BroadcastOptions.makeBasic().apply { setShareIdentityEnabled(true) }
            ctx.sendBroadcast(intent, null, options.toBundle())
        } else {
            ctx.sendBroadcast(intent)
        }
    }

    /** Whether a broadcast [receiver] is taking (in its onReceive) came from [pkg] (where the watch can tell). */
    fun from(receiver: BroadcastReceiver, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return receiver.sentFromPackage == pkg
    }
}

/** The LiquiWatch companion asks what's playing (it started afresh): told again at once. */
class NowPlayingRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NowPlayingLink.ACTION_REQUEST) return
        if (!NowPlayingLink.from(this, NowPlayingLink.COMPANION)) return
        // (The companion asks as a face puts its date pill on, or after it's restarted: the faces
        // are asked for the album art too, whether or not this app thinks one shows it, so a face
        // that does asks for it now, and is noted as showing it. An ask no face takes is no work.)
        FaceArt.refreshFaces(context)
        NowPlayingLink.update(context, force = true)
        FaceArt.finishWhenDone(goAsync())
    }
}
