package com.dewijones92.totum.innertube.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicLyricsParserTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("music/$name"),
    ) { "fixture $name missing" }.bufferedReader().use { it.readText() }

    @Test
    fun `a song's next page names the browse id of its lyrics`() {
        assertEquals("MPLYt_IIWn9LYU83g-7", MusicLyricsParser.lyricsBrowseId(fixture("radio.json")))
    }

    @Test
    fun `a song whose lyrics tab cannot be opened has none`() {
        val noEndpoint = fixture(
            "radio.json"
        ).replace("\"MUSIC_PAGE_TYPE_TRACK_LYRICS\"", "\"MUSIC_PAGE_TYPE_NOTHING\"")

        assertNull(MusicLyricsParser.lyricsBrowseId(noEndpoint))
    }

    @Test
    fun `the lyrics page gives the words and credits their source`() {
        val lyrics = MusicLyricsParser.lyrics(fixture("lyrics.json"))

        assertEquals(
            "Placeholder line one\nPlaceholder line two\n\nA second verse stands here\nAnd ends here",
            lyrics?.text,
        )
        assertEquals("Source: Musixmatch", lyrics?.source)
    }

    @Test
    fun `a lyrics page with no words is no lyrics`() {
        assertNull(MusicLyricsParser.lyrics("""{"contents":{"sectionListRenderer":{"contents":[]}}}"""))
    }
}
