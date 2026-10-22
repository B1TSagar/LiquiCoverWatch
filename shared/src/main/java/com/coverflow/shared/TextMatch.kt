package com.coverflow.shared

import java.security.MessageDigest
import java.util.Locale

/** Loose text matching so "Song (feat. X) - Remastered" and "Song" compare as the same. */
object TextMatch {
    private val afterDash = Regex("\\s[-\u2013\u2014]\\s.*$")
    private val brackets = Regex("\\(.*?\\)|\\[.*?]")
    private val nonWord = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val artistSplit = Regex("\\s*(,|&|/|;|\\bfeat\\.?|\\bft\\.?|\\bwith\\b|\\sx\\s)\\s*")

    /** Lowercase, drop bracketed parts and " - suffix", keep letters/marks/digits only. */
    fun loose(s: String): String = s.lowercase(Locale.ROOT)
        .replace(afterDash, "")
        .replace(brackets, " ")
        .replace(nonWord, " ")
        .trim()

    /** First credited artist, loosely normalised ("Arijit Singh, Pritam" -> "arijit singh"). */
    fun primaryArtist(artist: String): String {
        val first = artist.lowercase(Locale.ROOT).split(artistSplit).firstOrNull().orEmpty()
        return loose(first)
    }

    /** Rough similarity score used to pick catalog matches. */
    fun score(candidate: String, wanted: String): Int {
        val a = loose(candidate)
        val b = loose(wanted)
        if (b.isEmpty() || a.isEmpty()) return 0
        if (a == b) return 3
        if (a.contains(b) || b.contains(a)) return 2
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        val overlap = ta.intersect(tb).size.toFloat() / maxOf(1, minOf(ta.size, tb.size))
        return if (overlap >= 0.6f) 1 else -2
    }

    /**
     * Cover key: songs from the same album share one cover.
     * Falls back to the song title when the album is unknown.
     */
    fun artKey(artist: String, album: String, title: String): String {
        val basis = if (album.isNotBlank()) "al:${primaryArtist(artist)}|${loose(album)}"
        else "tr:${primaryArtist(artist)}|${loose(title)}"
        val digest = MessageDigest.getInstance("SHA-1").digest(basis.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
