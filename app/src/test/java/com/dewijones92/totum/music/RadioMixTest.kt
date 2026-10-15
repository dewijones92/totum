package com.dewijones92.totum.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.music.MusicSong
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioMixTest {

    private fun song(id: String) = MusicSong(
        id,
        "song $id",
        artist = null,
        album = null,
        durationSeconds = null,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )

    @Test
    fun `each seed's radio takes turns, its own seed first, with no song twice`() {
        val mixed = interleaveRadios(
            listOf(
                listOf(song("a"), song("a1"), song("shared"), song("a2")),
                listOf(song("b"), song("shared"), song("b1")),
                listOf(song("c")),
            ),
        )

        assertEquals(listOf("a", "b", "c", "a1", "shared", "b1", "a2"), mixed.map { it.videoId })
    }

    @Test
    fun `the mix is named by its first songs`() {
        assertEquals("One & Two", mixTitle(listOf("One", "Two")))
        assertEquals("One, Two + 2 more", mixTitle(listOf("One", "Two", "Three", "Four")))
    }
}
