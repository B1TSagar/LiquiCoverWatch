package com.coverflow.wear.face

import android.content.Context
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.coverflow.wear.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * "Song" for watch faces: where the playing song is, as a ranged value, in a tone of the album's
 * colour (Now Playing's slider colour, see [FaceArt.tone]). The LiquiWatch · Expedition face
 * draws it as a thin line round its compass glass with a clear glass knob at its end, as Now
 * Playing's progress slider, and paints its hands' lines and its compass glass in the colour.
 *
 * The song's time comes as when it started and when it ends, in seconds since local midnight
 * (min and max), and the value is where the song was when this was sent. A complication can't
 * tick by itself, and having the watch move the value on every second costs the face a redraw
 * of its slot every second, so the LiquiWatch · Expedition face works out where the song is from
 * its own clock, from min and max (on any other face it shows as a ring filled that far). It's
 * sent again whenever the music plays, pauses, skips or seeks (see [FaceArt.noteState]).
 *
 * While nothing plays (paused or stopped) it's still a ranged value, but one whose start is
 * below zero and with no colour, so the face knows nothing plays and goes back to its own
 * colour. (Sending no data at all instead would leave a face nothing to tell that by.)
 *
 * It has no tap action: on the LiquiWatch · Expedition face its slot covers the whole face (it
 * only passes numbers on), and a tap on the face's free space shouldn't open anything.
 */
class SongProgressComplicationService : SuspendingComplicationDataSourceService() {

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        FaceArt.setSlot(this, complicationInstanceId, showing = true)
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        FaceArt.setSlot(this, complicationInstanceId, showing = false)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // (How long the ask took to get here: see CoverArtComplicationService.)
        FaceUpdates.answered(this, SongProgressComplicationService::class.java)
        // (Never null: see CoverArtComplicationService.)
        if (request.complicationType != ComplicationType.RANGED_VALUE) return NoDataComplicationData()
        val ctx = this
        FaceArt.setSlot(ctx, request.complicationInstanceId, showing = true)
        if (!FaceArt.now(ctx).known) withContext(Dispatchers.IO) { FaceArt.loadKnownState(ctx) }
        val song = FaceArt.song(ctx) ?: return nothing(ctx)
        val nowMs = System.currentTimeMillis()
        // Long past its end with no word from the phone (it went out of reach): nothing.
        if (nowMs > song.endsAt + STALE_MS) return nothing(ctx)
        val now = FaceArt.now(ctx)
        val tone = now.artKey?.let { key -> withContext(FaceArt.decoder) { FaceArt.tone(ctx, key) } }
        return build(ctx, song, nowMs, tone ?: NO_TONE)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.RANGED_VALUE) return null
        val nowMs = System.currentTimeMillis()
        val preview = FaceArt.Song(startedAt = nowMs - 75_000L, endsAt = nowMs + 125_000L)
        return build(this, preview, nowMs, 0xFFF0A35E.toInt())
    }

    companion object {
        /** A song this long (ms) past its end with no new state from the phone shows nothing. */
        private const val STALE_MS = 30_000L

        /** The line's colour before the first cover's colour is known. */
        private const val NO_TONE = 0xFFE6E9F0.toInt()

        /** Asks every face showing the song's progress to fetch it again. */
        fun refresh(context: Context) {
            FaceUpdates.request(context, SongProgressComplicationService::class.java)
        }

        /** Nothing playing: a value below zero, with no colour (see the class's comment). */
        private fun nothing(context: Context): ComplicationData =
            RangedValueComplicationData.Builder(
                value = -1f,
                min = -1f,
                max = 0f,
                contentDescription = PlainComplicationText.Builder("Nothing playing").build(),
            )
                .setMonochromaticImage(
                    MonochromaticImage.Builder(Icon.createWithResource(context, R.drawable.ic_cover_art)).build(),
                )
                .build()

        /** Seconds since local midnight at [ms] (wall-clock), as the face's clock counts them. */
        private fun secondsOfDay(ms: Long): Float {
            val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
            return t.toSecondOfDay() + t.nano / 1e9f
        }

        private fun build(context: Context, song: FaceArt.Song, nowMs: Long, tone: Int): ComplicationData {
            // In whole seconds (the face passes the start on to its clock in whole seconds).
            val startMs = song.startedAt - Math.floorMod(song.startedAt, 1000L)
            val start = secondsOfDay(startMs)
            val length = ((song.endsAt - startMs) / 1000f).coerceAtLeast(1f)
            val elapsed = ((nowMs - startMs) / 1000f).coerceIn(0f, length)
            return RangedValueComplicationData.Builder(
                value = start + elapsed,
                min = start,
                max = start + length,
                contentDescription = PlainComplicationText.Builder("Progress of the playing song").build(),
            )
                .setMonochromaticImage(
                    MonochromaticImage.Builder(Icon.createWithResource(context, R.drawable.ic_cover_art)).build(),
                )
                .setColorRamp(ColorRamp(intArrayOf(tone, tone), interpolated = true))
                .build()
        }
    }
}
