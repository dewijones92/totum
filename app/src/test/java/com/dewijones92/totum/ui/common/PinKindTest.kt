package com.dewijones92.totum.ui.common

import com.dewijones92.totum.R
import com.dewijones92.totum.pins.Pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PinKindTest {

    @Test
    fun `a radio an album and every song by one artist each say what they are`() {
        val radio = Pin.Radio("v", "RDx", "Jackson 5", null)
        val album = Pin.Album("MPREb_x", "Jackson 5", null)
        val songs = Pin.ArtistSongs("VLx", null, "Jackson 5", null)
        assertEquals(R.string.pin_kind_radio, radio.kindWords())
        assertEquals(R.string.pin_kind_album, album.kindWords())
        assertEquals(R.string.pin_kind_artist_songs, songs.kindWords())
        assertEquals(3, setOf(radio, album, songs).map { it.kindWords() }.toSet().size)
    }

    @Test
    fun `a shuffled album says it is shuffled`() {
        assertNotEquals(
            Pin.Album("b", "t", null).kindWords(),
            Pin.Album("b", "t", null, shuffled = true).kindWords(),
        )
    }
}
