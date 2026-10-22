package com.coverflow.shared

import kotlin.math.max
import kotlin.math.min

/** Picks a cover's dominant colour (the phone does this and sends it with the cover). */
object CoverPalette {
    /** Edge (px) of the square the cover is sampled at. */
    const val SAMPLE = 24
    private const val BUCKETS = 24 // hue buckets of 15 degrees

    /**
     * The most prominent *vivid* colour in the cover, as ARGB (vivid pixels are weighted up so
     * a small red logo on grey doesn't lose to the grey). Falls back to the average for
     * greyscale covers. [pixels] is the cover scaled to [SAMPLE]×[SAMPLE], ARGB.
     */
    fun dominant(pixels: IntArray): Int {
        val weight = FloatArray(BUCKETS)
        val sumR = FloatArray(BUCKETS)
        val sumG = FloatArray(BUCKETS)
        val sumB = FloatArray(BUCKETS)
        var avgR = 0f
        var avgG = 0f
        var avgB = 0f
        val hsv = FloatArray(3)

        for (p in pixels) {
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            avgR += r
            avgG += g
            avgB += b
            toHsv(r, g, b, hsv)
            val s = hsv[1]
            val v = hsv[2]
            if (v < 0.15f || s < 0.18f) continue
            val w = s * s * v
            val bucket = ((hsv[0] / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            weight[bucket] += w
            sumR[bucket] += r * w
            sumG[bucket] += g * w
            sumB[bucket] += b * w
        }

        var best = -1
        var bestWeight = 0f
        for (i in 0 until BUCKETS) {
            val w = weight[i] + 0.5f * (weight[(i + 1) % BUCKETS] + weight[(i + BUCKETS - 1) % BUCKETS])
            if (w > bestWeight) {
                bestWeight = w
                best = i
            }
        }

        val n = max(pixels.size, 1).toFloat()
        // Less than ~4% of the cover is meaningfully coloured: treat it as greyscale.
        if (best < 0 || bestWeight < n * 0.04f * 0.3f) {
            return argb(avgR / n, avgG / n, avgB / n)
        }
        val ids = intArrayOf((best + BUCKETS - 1) % BUCKETS, best, (best + 1) % BUCKETS)
        val factors = floatArrayOf(0.5f, 1f, 0.5f)
        var w = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        for (k in ids.indices) {
            val i = ids[k]
            w += weight[i] * factors[k]
            r += sumR[i] * factors[k]
            g += sumG[i] * factors[k]
            b += sumB[i] * factors[k]
        }
        return argb(r / w, g / w, b / w)
    }

    private fun argb(r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = v.toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** Hue (0-360), saturation and value (0-1), as android.graphics.Color.colorToHSV gives them. */
    internal fun toHsv(r8: Int, g8: Int, b8: Int, out: FloatArray) {
        val r = r8 / 255f
        val g = g8 / 255f
        val b = b8 / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val range = hi - lo
        var h = when {
            range == 0f -> 0f
            hi == r -> 60f * (((g - b) / range) % 6f)
            hi == g -> 60f * ((b - r) / range + 2f)
            else -> 60f * ((r - g) / range + 4f)
        }
        if (h < 0f) h += 360f
        out[0] = h
        out[1] = if (hi == 0f) 0f else range / hi
        out[2] = hi
    }
}
