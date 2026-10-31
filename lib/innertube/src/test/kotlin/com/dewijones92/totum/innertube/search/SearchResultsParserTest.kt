package com.dewijones92.totum.innertube.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses a real captured WEB search response (2026-07-25). */
class SearchResultsParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "fixture $name missing" }
            .bufferedReader().readText()

    private val videos = SearchResultsParser.videos(fixture("search_web_sample.json"))

    @Test
    fun `search results parse with an upload date on every hit`() {
        assertTrue("expected results", videos.size >= 10)
        // The date is the whole reason this parser exists: yt-dlp's flat search
        // results don't carry one, and the WEB search response does.
        assertEquals("every hit dated", videos.size, videos.count { it.publishedText != null })
    }

    @Test
    fun `each hit carries the fields the search row renders`() {
        assertTrue("titles", videos.all { it.title.isNotBlank() })
        assertTrue("authors", videos.all { !it.author.isNullOrBlank() })
        assertTrue("durations", videos.all { it.durationSeconds != null })
        assertTrue("thumbnails", videos.all { it.thumbnailUrl != null })
        assertTrue(
            "watch urls built from the video id",
            videos.all { it.watchUrl.value == "https://www.youtube.com/watch?v=${it.videoId}" },
        )
    }

    /**
     * A hit that names its channel makes "go to channel" free; one that does not falls back to
     * discovering it with a full yt-dlp extraction — 6 to 25 seconds with the JS runtime. The
     * feed route was fixed first; this is the same bug arriving by the other door.
     *
     * Three byline fields can carry it and which are present varies by result shape, so all
     * three are tried rather than trusting any one.
     */
    @Test
    fun `hits name their channel, so navigating to one costs nothing`() {
        val named = videos.filter { it.channelId != null }

        assertEquals("12 of the 13 captured hits name a channel", 12, named.size)
        assertTrue(
            "and each is a real UC id",
            named.all { it.channelId!!.startsWith("UC") && it.channelId!!.length == 24 },
        )
    }

    /**
     * The one exception in the captured response, and it is not a parsing miss: a
     * COLLABORATION ("X and Y") has no `browseEndpoint` on its byline at all — YouTube puts a
     * `showDialogCommand` there instead, because there are two channels and it has to ask
     * which. So there is genuinely no single channel to name, and the engine fallback is the
     * right answer for those rather than something to be parsed harder.
     */
    @Test
    fun `a collaboration names no channel, because there is not one`() {
        val collaboration = videos.single { it.channelId == null }

        assertTrue(
            "the byline names more than one uploader: ${collaboration.author}",
            collaboration.author!!.contains(" and "),
        )
    }

    @Test
    fun `results are de-duplicated in relevance order`() {
        assertEquals(videos.map { it.videoId }.distinct(), videos.map { it.videoId })
    }

    @Test
    fun `a malformed body yields no results rather than throwing`() {
        assertTrue(SearchResultsParser.videos("not json").isEmpty())
        assertTrue(SearchResultsParser.videos("{}").isEmpty())
    }

    @Test
    fun `a live result and a Short are told apart from a video, as the feed tells them`() {
        fun hit(id: String, style: String, endpoint: String) = """
            {"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$id"}]},
              "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"style":"$style"}}],
              "navigationEndpoint":{"$endpoint":{"videoId":"$id"}}}}
        """
        val hits = listOf(
            hit("liveliveliv", "LIVE", "watchEndpoint"),
            hit("shortshorts", "SHORTS", "reelWatchEndpoint"),
            hit("videovideov", "DEFAULT", "watchEndpoint"),
        )
        val body = """{"contents":[${hits.joinToString(",")}]}"""

        val kinds = SearchResultsParser.videos(body).associate { it.videoId to it.kind }

        assertEquals(
            mapOf(
                "liveliveliv" to com.dewijones92.totum.innertube.feeds.FeedVideo.Kind.LIVE,
                "shortshorts" to com.dewijones92.totum.innertube.feeds.FeedVideo.Kind.SHORT,
                "videovideov" to com.dewijones92.totum.innertube.feeds.FeedVideo.Kind.VIDEO,
            ),
            kinds,
        )
    }
}
