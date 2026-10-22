package com.coverflow.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The matching that picks covers, album lists and songs to play (see ArtStore, AlbumLookup, Launcher). */
class TextMatchTest {
    @Test
    fun looseDropsDecorations() {
        assertEquals("song", TextMatch.loose("Song (feat. X) - Remastered"))
        assertEquals("hallelujah", TextMatch.loose("Hallelujah - Single"))
        assertEquals("greatest hits", TextMatch.loose("Greatest Hits [Deluxe]"))
    }

    @Test
    fun primaryArtistIsTheFirstCredited() {
        assertEquals("arijit singh", TextMatch.primaryArtist("Arijit Singh, Pritam"))
        assertEquals("queen", TextMatch.primaryArtist("Queen & David Bowie"))
        assertEquals("a", TextMatch.primaryArtist("A feat. B"))
        assertEquals("", TextMatch.primaryArtist(""))
    }

    @Test
    fun artKeyIsPerAlbumAndFirstArtist() {
        val a = TextMatch.artKey("Queen", "Greatest Hits", "Bohemian Rhapsody")
        assertEquals(a, TextMatch.artKey("Queen", "Greatest Hits (Remastered)", "Killer Queen"))
        assertNotEquals(a, TextMatch.artKey("ABBA", "Greatest Hits", "SOS"))
        // A soundtrack's tracks credit different singers first: different keys, one album name
        // (which is why the placeholder check counts albums, not artists).
        assertNotEquals(
            TextMatch.artKey("Arijit Singh, Pritam", "Film OST", "One"),
            TextMatch.artKey("Shreya Ghoshal", "Film OST", "Two"),
        )
        assertEquals(TextMatch.loose("Film OST"), TextMatch.loose("Film OST"))
        // No album: by title.
        assertNotEquals(TextMatch.artKey("X", "", "One"), TextMatch.artKey("X", "", "Two"))
    }

    @Test
    fun score() {
        assertEquals(3, TextMatch.score("Song (Live)", "song"))
        assertEquals(2, TextMatch.score("Lovely", "Love"))
        assertEquals(0, TextMatch.score("", "Love"))
        assertEquals(-2, TextMatch.score("Yesterday", "Love"))
    }

    @Test
    fun sameSong() {
        val love = Track(title = "Love", artist = "A")
        assertTrue(love.sameSongAs(Track(title = "Love - Remastered 2011", artist = "A, B")))
        assertTrue(love.sameSongAs(Track(title = "Love", artist = "")))
        assertFalse(love.sameSongAs(Track(title = "Lovely", artist = "A")))
        assertFalse(love.sameSongAs(Track(title = "Love", artist = "C")))
        assertFalse(love.sameSongAs(null))
    }
}
