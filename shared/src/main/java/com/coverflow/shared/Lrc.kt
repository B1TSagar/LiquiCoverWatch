package com.coverflow.shared

/** One timed lyric line. An empty [text] marks an instrumental gap. */
data class LyricLine(val timeMs: Long, val text: String)

/** Parser for LRC synced lyrics ("[01:23.45] line"), including multi-timestamp lines and [offset:]. */
object Lrc {
    private val leadingTag = Regex("^\\s*\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)
    private val wordTag = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")

    fun parse(lrc: String?): List<LyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        // Positive offset = show lyrics earlier.
        val offset = offsetTag.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        for (raw in lrc.lines()) {
            var rest = raw
            val times = ArrayList<Long>()
            while (true) {
                val m = leadingTag.find(rest) ?: break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                times += min * 60_000 + sec * 1_000 + fracMs
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isEmpty()) continue // metadata tags ([ar:], [ti:]) and junk
            val text = rest.replace(wordTag, "").trim()
            for (t in times) out += LyricLine((t - offset).coerceAtLeast(0L), text)
        }
        out.sortBy { it.timeMs }
        return out
    }

    /** Index of the line playing at [positionMs], or -1 before the first line. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}
