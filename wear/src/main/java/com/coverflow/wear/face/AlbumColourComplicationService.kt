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

/**
 * "Album colour" for watch faces: the playing song's colour, toned as Now Playing's sliders
 * (see [FaceArt.tone]), while the face shows its cover. Watch faces can't be handed a colour as
 * such, so it comes two ways in a full "ranged value": as its colour ramp, and as a number, the
 * minimum, which is the colour's red, green and blue as one number (red × 65536 + green × 256 +
 * blue, exact in a float). The LiquiWatch · Expedition face reads the number and paints its
 * hands' lines, its compass glass and its knob with it. While nothing plays (or a pause has
 * lasted a few seconds) the minimum is below zero, with no colour, and the face uses its own.
 * On other faces it shows as a full ring in the album's colour. It has no tap action (on the
 * Expedition face its slot is a dot at the hub, and a tap there shouldn't open anything).
 */
class AlbumColourComplicationService : SuspendingComplicationDataSourceService() {

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        FaceArt.setSlot(this, complicationInstanceId, showing = true)
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        FaceArt.setSlot(this, complicationInstanceId, showing = false)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // (How long the ask took to get here: see CoverArtComplicationService.)
        FaceUpdates.answered(this, AlbumColourComplicationService::class.java)
        // (Never null: see CoverArtComplicationService.)
        if (request.complicationType != ComplicationType.RANGED_VALUE) return NoDataComplicationData()
        val ctx = this
        FaceArt.setSlot(ctx, request.complicationInstanceId, showing = true)
        if (!FaceArt.now(ctx).known) withContext(Dispatchers.IO) { FaceArt.loadKnownState(ctx) }
        val now = FaceArt.now(ctx)
        val key = now.artKey
        val tone = if (key != null && FaceArt.showsArt(now)) withContext(FaceArt.decoder) { FaceArt.tone(ctx, key) } else null
        return build(ctx, tone)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.RANGED_VALUE) build(this, 0xFFF0A35E.toInt()) else null

    companion object {
        /** Asks every face showing the album colour to fetch it again. */
        fun refresh(context: Context) {
            FaceUpdates.request(context, AlbumColourComplicationService::class.java)
        }

        /**
         * Full, in [tone] (the same colour at both ends), with the colour as the minimum; or empty
         * and colourless with no song, its minimum below zero.
         */
        private fun build(context: Context, tone: Int?): ComplicationData {
            val description = PlainComplicationText.Builder(
                if (tone != null) "Colour of the playing album" else "Nothing playing",
            ).build()
            val rgb = (tone ?: 0) and 0xFFFFFF
            val builder = RangedValueComplicationData.Builder(
                value = if (tone != null) rgb + 1f else -1f,
                min = if (tone != null) rgb.toFloat() else -1f,
                max = if (tone != null) rgb + 1f else 0f,
                contentDescription = description,
            )
                .setMonochromaticImage(
                    MonochromaticImage.Builder(Icon.createWithResource(context, R.drawable.ic_cover_art)).build(),
                )
            if (tone != null) builder.setColorRamp(ColorRamp(intArrayOf(tone, tone), interpolated = true))
            return builder.build()
        }
    }
}
