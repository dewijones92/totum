package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaysAtSpeedTest {

    @Test
    fun `music and live streams always play at normal speed whatever was chosen`() {
        listOf(0.75f, 1f, 2f, 3f).forEach { chosen ->
            assertEquals(1f, MediaContentKind.MUSIC.playsAtSpeed(chosen))
            assertEquals(1f, MediaContentKind.LIVE.playsAtSpeed(chosen))
        }
    }

    @Test
    fun `every other kind plays at the chosen speed, including an ended stream`() {
        listOf(
            MediaContentKind.STANDARD,
            MediaContentKind.SHORT,
            MediaContentKind.AUDIOBOOK,
            MediaContentKind.WAS_LIVE,
        ).forEach { kind ->
            assertEquals("$kind", 1.75f, kind.playsAtSpeed(1.75f))
        }
    }

    @Test
    fun `every kind answers, so a new kind cannot be added without choosing`() {
        MediaContentKind.entries.forEach { kind ->
            val speed = kind.playsAtSpeed(2f)
            assert(speed == 1f || speed == 2f) { "$kind answered $speed" }
        }
    }

    @Test
    fun `silence skipping follows the same kinds with its own exceptions`() {
        assertEquals(false, MediaContentKind.MUSIC.skipsSilence(true))
        assertEquals(false, MediaContentKind.LIVE.skipsSilence(true))
        assertEquals(false, MediaContentKind.AUDIOBOOK.skipsSilence(true))
        assertEquals(true, MediaContentKind.STANDARD.skipsSilence(true))
        assertEquals(true, MediaContentKind.WAS_LIVE.skipsSilence(true))
        assertEquals(false, MediaContentKind.STANDARD.skipsSilence(false))
    }
}
