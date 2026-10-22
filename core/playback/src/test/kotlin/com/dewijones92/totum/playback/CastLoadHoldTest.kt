package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastLoadHoldTest {

    private var clock = 0L
    private val hold = CastLoadHold({ clock })
    private val playing = PlaybackState(
        itemId = MediaItemId("a"),
        title = "A",
        artist = null,
        artworkUrl = null,
        isPlaying = true,
        positionMs = 5_000,
        durationMs = 60_000,
        speed = 1f,
    )

    @Test
    fun `while the receiver loads, the handed-over item still shows, as loading`() {
        hold.seen(playing)
        clock = 12_000
        val shown = hold.nothingCurrent(onCastDevice = true, castSession = true)
        assertEquals(MediaItemId("a") to true, shown?.itemId to shown?.isBuffering)
    }

    @Test
    fun `nothing is held off the TV, after an end, or for ever`() {
        hold.seen(playing)
        assertNull(hold.nothingCurrent(onCastDevice = false, castSession = true))
        hold.seen(playing.copy(hasEnded = true))
        assertNull(hold.nothingCurrent(onCastDevice = true, castSession = true))
        hold.seen(playing)
        clock = 31_000
        assertNull(hold.nothingCurrent(onCastDevice = true, castSession = true))
    }
}
