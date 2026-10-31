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
                ItemTuning(
                    speed = 1.75f,
                    skipSilence = true,
                    keepsChanges = true,
                    silentTailEnds = kind != MediaContentKind.LIVE
                ),
                tuningFor(kind, choices)
            )
        }
    }

    @Test
    fun `a silent tail ends talk, never music or a live stream`() {
        assertEquals(true, tuningFor(MediaContentKind.STANDARD, choices).silentTailEnds)
        assertEquals(false, tuningFor(MediaContentKind.MUSIC, choices).silentTailEnds)
        assertEquals(false, tuningFor(MediaContentKind.LIVE, choices).silentTailEnds)
    }

    @Test
    fun `a book keeps your speed, never skips silence, and a skip-silence toggle is for that chapter only`() {
        assertEquals(
            ItemTuning(
                speed = 1.75f,
                skipSilence = false,
                keepsChanges = true,
                silentTailEnds = false,
                keepsSkipSilence = false,
            ),
            tuningFor(MediaContentKind.AUDIOBOOK, choices)
        )
    }

    @Test
    fun `only a book stops skip-silence while keeping other changes`() {
        MediaContentKind.entries.forEach { kind ->
            val tuning = tuningFor(kind, choices)
            assertEquals(kind.name, kind != MediaContentKind.AUDIOBOOK && tuning.keepsChanges, tuning.keepsSkipSilence)
        }
    }
}
