package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaContentKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicPlaysPlainTest {

    private val choices = PlaybackChoices(speed = 1.75f, skipSilence = true)

    @Test
    fun `a song plays at normal speed with nothing cut, whatever was chosen for talk`() {
        assertEquals(
            ItemTuning(speed = 1f, skipSilence = false, keepsChanges = false),
            tuningFor(MediaContentKind.MUSIC, choices)
        )
    }

    @Test
    fun `everything else plays with the choices you made`() {
        listOf(MediaContentKind.STANDARD, MediaContentKind.SHORT, MediaContentKind.LIVE).forEach { kind ->
            assertEquals(
                kind.name,
                ItemTuning(speed = 1.75f, skipSilence = true, keepsChanges = true),
                tuningFor(kind, choices)
            )
        }
    }
}
