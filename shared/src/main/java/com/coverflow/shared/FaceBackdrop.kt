package com.coverflow.shared

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What a watch face shows behind and around the playing song (the LiquiWatch · Expedition face):
 * the cover blurred and darkened as behind every screen of the app, and the cover's colour toned
 * as Now Playing's sliders.
 *
 * The phone makes both ([FaceArtPacket]); the watch only stores and shows them, and never makes
 * or compresses one itself.
 */
object FaceBackdrop {
    /**
     * The background's edge (px), as the watch hands it to the face: the size the blur is worked
     * out at. It's a soft blur, so the face scales it up to the screen without losing anything:
     * scaled up on the phone first, to 256 px, it came out on the face the same, give or take the
     * noise in it (a few levels at most, measured on test covers), as a picture four and a half
     * times as big (256 KB for the watch to keep, hand to the face and send to its graphics chip
     * with every song, where this is 57 KB).
     */
    const val SIZE = 120

    /** The blur is worked out at this size (px). */
    private const val WORK = SIZE

    /**
     * Box blur radius at [WORK] px, three passes: a little sharper than the app's 20 dp blur
     * (about a fifth less), so the cover reads a little more clearly behind the face.
     */
    private const val BLUR_RADIUS = 5

    /** As the app darkens its blurred covers (a 55% black scrim), with the colour kept up. */
    private const val BRIGHTNESS = 0.47f
    private const val SATURATION = 1.25f

    /**
     * The round cover's edge (px): the LiquiWatch · Expedition face shows it in its date pill's
     * window, where the moon is, at exactly this size (one of the watch's pixels for each of its
     * own), so the watch never scales it.
     */
    const val ROUND_SIZE = 38

    /** The round cover's radius (px): as the moon's, a pixel clear all round inside [ROUND_SIZE]. */
    private const val ROUND_RADIUS = 18f

    /**
     * The cover, round, [ROUND_SIZE] px (the middle square, its corners clear): shrunk in halves,
     * each step averaging its pixels, then the last of the way (one step from a few hundred
     * pixels down would take one pixel in ten and shimmer); the circle's edge smoothed, each edge
     * pixel as opaque as the share of it inside (worked out on a 4 x 4 grid).
     */
    fun round(cover: Bitmap): Bitmap {
        val side = min(cover.width, cover.height)
        val square = if (cover.width == cover.height) {
            cover
        } else {
            Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
        }
        var img = square
        while (img.width / 2 >= ROUND_SIZE) {
            val half = Bitmap.createScaledBitmap(img, img.width / 2, img.height / 2, true)
            if (img !== square) img.recycle()
            img = half
        }
        val n = ROUND_SIZE
        val small = if (img.width == n) img else Bitmap.createScaledBitmap(img, n, n, true)
        if (img !== small && img !== square) img.recycle()
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        if (small !== square) small.recycle()
        if (square !== cover) square.recycle()
        val c = n / 2f
        val r2 = ROUND_RADIUS * ROUND_RADIUS
        for (y in 0 until n) {
            for (x in 0 until n) {
                var inside = 0
                for (sy in 0 until 4) {
                    val dy = y + (sy + 0.5f) / 4f - c
                    for (sx in 0 until 4) {
                        val dx = x + (sx + 0.5f) / 4f - c
                        if (dx * dx + dy * dy <= r2) inside++
                    }
                }
                val i = y * n + x
                px[i] = ((inside * 255 + 8) / 16 shl 24) or (px[i] and 0xFFFFFF)
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** The cover blurred, darkened and a little more colourful, as behind the app's screens. */
    fun blurred(cover: Bitmap): Bitmap {
        // The middle square (covers are square already, but just in case).
        val side = min(cover.width, cover.height)
        val square = if (cover.width == cover.height) {
            cover
        } else {
            Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
        }
        val n = WORK
        val small = Bitmap.createScaledBitmap(square, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        val r = FloatArray(n * n)
        val g = FloatArray(n * n)
        val b = FloatArray(n * n)
        for (i in px.indices) {
            val c = px[i]
            r[i] = ((c shr 16) and 0xFF).toFloat()
            g[i] = ((c shr 8) and 0xFF).toFloat()
            b[i] = (c and 0xFF).toFloat()
        }
        val tmp = FloatArray(n * n)
        for (channel in arrayOf(r, g, b)) {
            repeat(3) { boxBlur(channel, tmp, n, BLUR_RADIUS) }
        }
        // A whisper of noise, so the soft gradients don't band once the face scales them up.
        val noise = Random(7)
        for (i in px.indices) {
            val l = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
            fun tone(v: Float): Int {
                val d = (noise.nextFloat() - noise.nextFloat()) * 0.9f
                return ((l + (v - l) * SATURATION) * BRIGHTNESS + d).toInt().coerceIn(0, 255)
            }
            px[i] = (0xFF shl 24) or (tone(r[i]) shl 16) or (tone(g[i]) shl 8) or tone(b[i])
        }
        val out = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        if (small !== square) small.recycle()
        if (square !== cover) square.recycle()
        return out
    }

    /** One pass of a box blur of [radius] across and then down (edges held), in place. */
    private fun boxBlur(c: FloatArray, tmp: FloatArray, n: Int, radius: Int) {
        val span = (2 * radius + 1).toFloat()
        for (y in 0 until n) {
            val row = y * n
            var sum = 0f
            for (k in -radius..radius) sum += c[row + k.coerceIn(0, n - 1)]
            for (x in 0 until n) {
                tmp[row + x] = sum / span
                sum += c[row + (x + radius + 1).coerceAtMost(n - 1)] - c[row + (x - radius).coerceAtLeast(0)]
            }
        }
        for (x in 0 until n) {
            var sum = 0f
            for (k in -radius..radius) sum += tmp[k.coerceIn(0, n - 1) * n + x]
            for (y in 0 until n) {
                c[y * n + x] = sum / span
                sum += tmp[(y + radius + 1).coerceAtMost(n - 1) * n + x] - tmp[(y - radius).coerceAtLeast(0) * n + x]
            }
        }
    }

    /**
     * The face's colour for a cover whose own colour is [argb] ([CoverPalette.dominant]): Now
     * Playing's slider colour, a tone of it that stands out on the blurred cover. Dark colours get
     * lighter, very pale ones a little darker, and the hue is kept, with enough saturation to
     * read as that colour; greys and black-and-white covers get a light silver. (The same as
     * the watch app's CoverColors.sliderTone, on plain ARGB.)
     */
    fun tone(argb: Int): Int {
        val alpha = ((argb ushr 24) and 0xFF) / 255f
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val span = hi - lo
        val light = (hi + lo) / 2f
        val sat = if (span < 0.0001f) 0f else span / (1f - abs(2f * light - 1f)).coerceAtLeast(0.0001f)
        val hue = when {
            span < 0.0001f -> 0f
            hi == r -> ((g - b) / span).mod(6f)
            hi == g -> (b - r) / span + 2f
            else -> (r - g) / span + 4f
        } * 60f
        if (sat < 0.12f) return hsl(hue, sat, 0.86f, alpha)
        val newLight = when {
            light > 0.80f -> 0.72f // very pale: a little darker
            light < 0.62f -> 0.64f // dark or deep: lighter
            else -> light
        }
        return hsl(hue, sat.coerceIn(0.55f, 0.90f), newLight, alpha)
    }

    private fun hsl(hue: Float, sat: Float, light: Float, alpha: Float): Int {
        val chroma = (1f - abs(2f * light - 1f)) * sat
        val h = hue / 60f
        val x = chroma * (1f - abs(h.mod(2f) - 1f))
        val (r, g, b) = when {
            h < 1f -> Triple(chroma, x, 0f)
            h < 2f -> Triple(x, chroma, 0f)
            h < 3f -> Triple(0f, chroma, x)
            h < 4f -> Triple(0f, x, chroma)
            h < 5f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val m = light - chroma / 2f
        fun c(v: Float) = ((v + m).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}

/**
 * The face's background and colour for one cover, as the phone sends them (message
 * [Paths.FACE_ART_PREFIX] + art key): the blurred cover, [FaceBackdrop.SIZE] px, ready to show
 * ([image], JPEG), and the colour ([tone], ARGB); and the cover round, for the face's date pill
 * ([round], PNG, [FaceBackdrop.ROUND_SIZE] px; none from an older phone app).
 */
class FaceArtPacket(val image: ByteArray, val tone: Int, val round: ByteArray? = null) {
    fun toBytes(): ByteArray {
        val bytes = ByteArrayOutputStream(image.size + (round?.size ?: 0) + 24)
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(tone)
            out.writeInt(image.size)
            out.write(image)
            // (After the background, where a watch app that doesn't know of it stops reading.)
            round?.let {
                out.writeInt(ROUND_MAGIC)
                out.writeInt(it.size)
                out.write(it)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        /** "CFF1": face art, format 1. */
        const val MAGIC = 0x43464631

        /** "CFRC": the round cover, after the background. */
        private const val ROUND_MAGIC = 0x43465243

        fun fromBytes(data: ByteArray): FaceArtPacket? = try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                if (input.readInt() != MAGIC) return null
                val tone = input.readInt()
                val size = input.readInt()
                if (size !in 1..data.size) return null
                val image = ByteArray(size).also { input.readFully(it) }
                var round: ByteArray? = null
                if (input.available() >= 8 && input.readInt() == ROUND_MAGIC) {
                    val n = input.readInt()
                    if (n in 1..input.available()) round = ByteArray(n).also { input.readFully(it) }
                }
                FaceArtPacket(image, tone, round)
            }
        } catch (e: Exception) {
            null
        }
    }
}
