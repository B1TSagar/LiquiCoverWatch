package com.coverflow.mobile

import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A coarse summary of a cover that doesn't depend on its size: the average colour of each cell
 * of a [GRID] x [GRID] grid. A thumbnail and a big copy of the same picture look the same, so
 * this tells whether a sharper copy found elsewhere really is the same cover, and gives a hash
 * for spotting placeholder images.
 */
class CoverLook private constructor(private val cells: FloatArray) {

    /** Same placeholder picture = same hash, whatever size it came in. */
    val hash: Long

    /** Nearly a single flat colour (usually a placeholder). */
    val flat: Boolean

    init {
        val crc = CRC32()
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until GRID * GRID) {
            val r = cells[i * 3]
            val g = cells[i * 3 + 1]
            val b = cells[i * 3 + 2]
            crc.update(q(r))
            crc.update(q(g))
            crc.update(q(b))
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            sum += lum
            sumSq += lum * lum
        }
        hash = crc.value
        val n = GRID * GRID
        val mean = sum / n
        flat = sqrt(max(0.0, sumSq / n - mean * mean)) < FLAT_STD
    }

    /**
     * Whether [other] is the same picture, allowing for size, compression and small colour
     * shifts. The colours must be close, and the light/dark pattern must match too: on dark or
     * muted covers every picture is "close" in colour, so the pattern is what tells them apart.
     */
    fun sameAs(other: CoverLook): Boolean {
        var total = 0f
        var bad = 0
        for (i in 0 until GRID * GRID) {
            val d = (abs(cells[i * 3] - other.cells[i * 3]) +
                abs(cells[i * 3 + 1] - other.cells[i * 3 + 1]) +
                abs(cells[i * 3 + 2] - other.cells[i * 3 + 2])) / 3f
            total += d
            if (d > CELL_LIMIT) bad++
        }
        if (total / (GRID * GRID) > MEAN_LIMIT || bad > GRID * GRID * BAD_CELLS) return false
        return correlation(other) >= MIN_CORRELATION
    }

    /** Pattern similarity, -1..1: how well the two images' ups and downs line up, per colour channel. */
    private fun correlation(other: CoverLook): Float {
        var sum = 0.0
        for (ch in 0 until 3) {
            var ma = 0.0
            var mb = 0.0
            for (i in 0 until GRID * GRID) {
                ma += cells[i * 3 + ch]
                mb += other.cells[i * 3 + ch]
            }
            ma /= GRID * GRID
            mb /= GRID * GRID
            var ab = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in 0 until GRID * GRID) {
                val a = cells[i * 3 + ch] - ma
                val b = other.cells[i * 3 + ch] - mb
                ab += a * b
                aa += a * a
                bb += b * b
            }
            // A channel with no variation in either image (e.g. pure greys) carries no pattern.
            sum += if (aa < 1e-6 || bb < 1e-6) 1.0 else ab / sqrt(aa * bb)
        }
        return (sum / 3).toFloat()
    }

    companion object {
        const val GRID = 16
        private const val FLAT_STD = 0.045
        private const val MEAN_LIMIT = 0.07f
        private const val CELL_LIMIT = 0.2f
        private const val BAD_CELLS = 0.1f
        private const val MIN_CORRELATION = 0.85f

        private fun q(v: Float) = (v * 15.999f).toInt().coerceIn(0, 15)

        /** From ARGB [pixels] of a [width] x [height] image (row by row). */
        fun of(pixels: IntArray, width: Int, height: Int): CoverLook {
            val sums = DoubleArray(GRID * GRID * 3)
            val counts = IntArray(GRID * GRID)
            for (y in 0 until height) {
                val cy = y * GRID / height
                val row = y * width
                for (x in 0 until width) {
                    val cell = cy * GRID + x * GRID / width
                    val p = pixels[row + x]
                    sums[cell * 3] += ((p shr 16) and 0xff).toDouble()
                    sums[cell * 3 + 1] += ((p shr 8) and 0xff).toDouble()
                    sums[cell * 3 + 2] += (p and 0xff).toDouble()
                    counts[cell]++
                }
            }
            val cells = FloatArray(GRID * GRID * 3)
            for (c in 0 until GRID * GRID) {
                val n = max(1, counts[c]) * 255.0
                cells[c * 3] = (sums[c * 3] / n).toFloat()
                cells[c * 3 + 1] = (sums[c * 3 + 1] / n).toFloat()
                cells[c * 3 + 2] = (sums[c * 3 + 2] / n).toFloat()
            }
            return CoverLook(cells)
        }
    }
}
