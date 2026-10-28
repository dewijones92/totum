package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class SoundOnlyByNameTest {

    @Test
    fun `names that say XviD, DivX, AVI or AV1 are sound only on a TV`() {
        assertEquals("XviD", soundOnlyOnTheTvByName("Da Ali G Show S01E03 DVDRip XviD-SAiNTS"))
        assertEquals("DivX", soundOnlyOnTheTvByName("Old.Film.1961.DivX5.mp3"))
        assertEquals("AVI", soundOnlyOnTheTvByName("The Apprentice S03E01.avi"))
        assertEquals("AV1", soundOnlyOnTheTvByName("Nosferatu 1922 1080p AV1 Opus"))
    }

    @Test
    fun `names that cast with their picture say nothing`() {
        assertEquals(null, soundOnlyOnTheTvByName("Night of the Living Dead 1968 1080p BluRay x264"))
        assertEquals(null, soundOnlyOnTheTvByName("Sherlock Jr 1924 720p HEVC x265"))
        assertEquals(null, soundOnlyOnTheTvByName("Daviddson Travel Show"))
        assertEquals(null, soundOnlyOnTheTvByName("Pavilion AV10 tour"))
    }
}
