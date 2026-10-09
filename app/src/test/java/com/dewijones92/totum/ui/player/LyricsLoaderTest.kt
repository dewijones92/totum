package com.dewijones92.totum.ui.player

import com.dewijones92.totum.innertube.music.Lyrics
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsLoaderTest {

    private val words = Lyrics("Placeholder line one\nPlaceholder line two", "Source: Musixmatch")

    @Test
    fun `a song with lyrics shows them with their source`() = runTest {
        val catalogue = FakeYouTubeMusicCatalogue(lyricsById = mapOf("BNMKGYiJpvg" to words))

        assertEquals(LyricsState.Shown(words), LyricsLoader("BNMKGYiJpvg", catalogue).load())
        assertEquals(listOf("BNMKGYiJpvg"), catalogue.lyricsRequests)
    }

    @Test
    fun `a song YouTube Music has no lyrics for says so`() = runTest {
        assertEquals(LyricsState.None, LyricsLoader("instrument1", FakeYouTubeMusicCatalogue()).load())
    }

    @Test
    fun `a failed lookup is a failure to retry, not a song without lyrics`() = runTest {
        val failing = object : YouTubeMusicCatalogue by FakeYouTubeMusicCatalogue() {
            override suspend fun lyrics(videoId: String): MusicResult<Lyrics?> = MusicResult.Failure("HTTP 503")
        }

        assertEquals(LyricsState.Failed("HTTP 503"), LyricsLoader("BNMKGYiJpvg", failing).load())
    }
}
