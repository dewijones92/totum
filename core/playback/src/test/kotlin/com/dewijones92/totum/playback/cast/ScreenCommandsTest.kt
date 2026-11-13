package com.dewijones92.totum.playback.cast

import androidx.media3.common.Player
import com.dewijones92.totum.playback.SeekSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCommandsTest {

    @Test
    fun `the PC screen accepts seek back, seek forward, next and previous, as the phone's own player does`() {
        val missing = listOf(
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        ).filterNot(SCREEN_COMMAND_IDS::contains)

        assertTrue("the screen refuses commands $missing", missing.isEmpty())
    }

    @Test
    fun `the phone, the TV and the screen step 10 seconds back and 30 forward from one place`() {
        assertEquals(10_000L, SeekSteps.BACK_MS)
        assertEquals(30_000L, SeekSteps.FORWARD_MS)
    }
}
