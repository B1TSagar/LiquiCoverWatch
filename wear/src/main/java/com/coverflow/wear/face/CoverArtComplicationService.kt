package com.coverflow.wear.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Album art" for watch faces: the playing song's cover, blurred, as a full-screen photo (the
 * LiquiWatch · Expedition face puts it behind everything, and any face with a photo slot can
 * pick it), or as a small image in photo style for a face or watch that asks for that instead.
 * Nothing while nothing plays, so the face shows its own background. It has no tap action: as a
 * background it lies under the whole face, and a tap on the face's free space shouldn't open
 * anything. See [FaceArt].
 */
class CoverArtComplicationService : SuspendingComplicationDataSourceService() {

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        FaceArt.setSlot(this, complicationInstanceId, showing = true)
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        FaceArt.setSlot(this, complicationInstanceId, showing = false)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // (How long the ask took to get here: asks are timed by it, to reach the face just after
        // one of the seconds hand's beats.)
        FaceUpdates.answered(this, CoverArtComplicationService::class.java)
        val type = request.complicationType
        // (Never null: the complications library waits for an answer that a null never gives,
        // and the update it was asked for, and any after it in the same batch, never reach the face.)
        if (type != ComplicationType.PHOTO_IMAGE && type != ComplicationType.SMALL_IMAGE) return NoDataComplicationData()
        val ctx = this
        // (Also noted here in case the face came from an older install and was never "activated".)
        FaceArt.setSlot(ctx, request.complicationInstanceId, showing = true)
        // Just put on the face: start from the phone's last state, which the watch keeps.
        if (!FaceArt.now(ctx).known) withContext(Dispatchers.IO) { FaceArt.loadKnownState(ctx) }
        val now = FaceArt.now(ctx)
        val key = now.artKey
        if (key == null || !FaceArt.showsArt(now)) {
            Log.i(TAG, "Album art for a face ($type): none, nothing plays")
            return NoDataComplicationData()
        }

        // Not here yet: ask the phone, which makes it (the face updates as it arrives). In the
        // background: the face gets what's here now, rather than waiting on the phone.
        if (!FaceArt.hasFaceArt(ctx, key)) FaceArt.fetchLater(ctx, key)
        // (Kept decoded as it came; read from its file only after the app was restarted.)
        val background = withContext(FaceArt.decoder) { FaceArt.background(ctx, key) }
        if (background != null) {
            Log.i(TAG, "Album art for a face ($type): $key, ${background.width} px")
            return picture(ctx, background, type)
        }
        // Meanwhile the last song's background stays, rather than flashing the face's own.
        val last = withContext(FaceArt.decoder) { FaceArt.lastBackground(ctx) }
        Log.i(TAG, "Album art for a face ($type): $key isn't here yet, ${if (last != null) "the last song's meanwhile" else "none"}")
        return if (last != null) picture(ctx, last, type) else NoDataComplicationData()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.PHOTO_IMAGE && type != ComplicationType.SMALL_IMAGE) return null
        // A soft blur of colour, standing in for a cover.
        val n = 96
        val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF0B1426.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((x, y, color) in listOf(Triple(0.3f, 0.35f, 0xFFB8336A.toInt()), Triple(0.72f, 0.62f, 0xFF2E6FD8.toInt()))) {
            paint.shader = RadialGradient(x * n, y * n, n * 0.55f, color, 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, n.toFloat(), n.toFloat(), paint)
        }
        return picture(this, bitmap, type)
    }

    companion object {
        private const val TAG = "CoverFlowFace"

        /** Asks every face showing the album art to fetch it again. */
        fun refresh(context: Context) {
            FaceUpdates.request(context, CoverArtComplicationService::class.java)
        }

        private fun picture(context: Context, bitmap: Bitmap, type: ComplicationType): ComplicationData {
            val description = PlainComplicationText.Builder("Album art of the playing song").build()
            if (type == ComplicationType.SMALL_IMAGE) {
                return SmallImageComplicationData.Builder(
                    SmallImage.Builder(Icon.createWithBitmap(bitmap), SmallImageType.PHOTO).build(),
                    description,
                ).build()
            }
            return PhotoImageComplicationData.Builder(Icon.createWithBitmap(bitmap), description).build()
        }
    }
}
