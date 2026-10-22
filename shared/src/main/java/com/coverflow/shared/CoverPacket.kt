package com.coverflow.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A cover as the phone prepares it for the watch, ready to show: the picture already cut to the
 * size the watch draws it at ([image], WebP), its dominant colour ([color], ARGB) and the edge
 * of the original picture ([sourceSide] px, which tells a small thumbnail from a sharp cover).
 *
 * The format still has room for a line drawing of the cover, which earlier versions sent for
 * the backgrounds; it's now always empty and skipped, so older and newer apps still understand
 * each other.
 */
class CoverPacket(
    val image: ByteArray,
    val color: Int,
    val sourceSide: Int,
) {
    fun toBytes(): ByteArray {
        val bytes = ByteArrayOutputStream(image.size + 32)
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(sourceSide)
            out.writeInt(color)
            out.writeInt(image.size)
            out.write(image)
            out.writeInt(0) // no line drawing
        }
        return bytes.toByteArray()
    }

    companion object {
        /** "CFP2": cover packet, format 2. */
        const val MAGIC = 0x43465032

        fun fromBytes(data: ByteArray): CoverPacket? = try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                if (input.readInt() != MAGIC) return null
                val side = input.readInt()
                val color = input.readInt()
                val imageSize = input.readInt()
                if (imageSize !in 1..data.size) return null
                val image = ByteArray(imageSize).also { input.readFully(it) }
                val drawingSize = input.readInt() // a line drawing from an older phone: skipped
                if (drawingSize !in 0..data.size) return null
                input.readFully(ByteArray(drawingSize))
                CoverPacket(image, color, side)
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Watch asks for many covers at once, nearest to what's on screen first: [size] is the edge
 * (px) it draws covers at, and each item is an art key with the edge of the copy the watch
 * already has (0 = none; the phone only answers if it has something sharper).
 */
data class CoverBatchRequest(val size: Int, val items: List<Pair<String, Int>>) {
    fun toBytes(): ByteArray = buildString {
        append(size)
        for ((key, have) in items) append('\n').append(key).append('\t').append(have)
    }.toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(data: ByteArray): CoverBatchRequest? {
            val lines = String(data, Charsets.UTF_8).split('\n')
            val size = lines.firstOrNull()?.trim()?.toIntOrNull() ?: return null
            val items = lines.drop(1).mapNotNull { line ->
                val parts = line.split('\t')
                val key = parts[0].trim()
                if (key.isEmpty()) null else key to (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
            }
            return CoverBatchRequest(size, items)
        }
    }
}
