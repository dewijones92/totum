package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingTest {

    private val base = PlaybackState(
        itemId = MediaItemId("x"),
        title = "x",
        artist = null,
        artworkUrl = null,
        isPlaying = false,
        positionMs = 0,
        durationMs = null,
        speed = 1f,
    )

    @Test
    fun `wanting to play while buffering is loading`() {
        assertTrue(base.copy(isBuffering = true, wantsToPlay = true).isLoading())
    }

    @Test
    fun `buffering while paused is not loading, so Play stays pressable`() {
        assertFalse(base.copy(isBuffering = true, wantsToPlay = false).isLoading())
    }

    @Test
    fun `an item still starting is loading before the player has it`() {
        assertTrue(base.isLoading(starting = true))
        assertFalse(base.copy(isPlaying = true, wantsToPlay = true).isLoading())
    }
}
