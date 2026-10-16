package com.dewijones92.totum.innertube.related

import com.dewijones92.totum.innertube.feeds.FeedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedVideosParserTest {

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/related_web_sample.json")) { "fixture missing" }
            .bufferedReader().readText()

    private fun parsed(): List<FeedVideo> =
        (RelatedVideosParser.parse(fixture()) as RelatedResult.Success).videos

    @Test
    fun `the anonymous watch page also gives the playing video's description`() {
        val body = checkNotNull(javaClass.getResourceAsStream("/watch_page_description_web_sample.json"))
            .bufferedReader().readText()

        val description = (RelatedVideosParser.parse(body) as RelatedResult.Success).description

        assertTrue("got $description", description.orEmpty().startsWith("The official video for"))
    }

    @Test
    fun `collects video lockups in order, deduped, ignoring channel lockups`() {
        assertEquals(listOf("rel00000001", "rel00000002"), parsed().map { it.videoId })
    }

    @Test
    fun `maps title, author, thumbnail and watch url`() {
        val first = parsed().first()
        assertEquals("First Related", first.title)
        assertEquals("Alpha Channel", first.author)
        assertEquals("https://www.youtube.com/watch?v=rel00000001", first.watchUrl.value)
        assertEquals("https://i.ytimg.com/rel00000001/hq.jpg", first.thumbnailUrl?.value)
    }

    @Test
    fun `parses m ss and h mm ss durations`() {
        val byId = parsed().associateBy { it.videoId }
        assertEquals(12L * 60 + 34, byId.getValue("rel00000001").durationSeconds)
        assertEquals(1L * 3600 + 2 * 60 + 3, byId.getValue("rel00000002").durationSeconds)
    }

    @Test
    fun `skips a non-clock badge like Now playing to find the real duration`() {
        // rel00000002's first badge is "Now playing"; the duration is the second.
        assertEquals(1L * 3600 + 2 * 60 + 3, parsed().first { it.videoId == "rel00000002" }.durationSeconds)
    }

    @Test
    fun `a deduped lockup keeps the first-seen fields`() {
        assertEquals("First Related", parsed().first { it.videoId == "rel00000001" }.title)
    }

    @Test
    fun `unparseable json is a failure value`() {
        assertTrue(RelatedVideosParser.parse("not json") is RelatedResult.Failure)
    }

    @Test
    fun `an empty response yields no videos`() {
        assertNull(parsed().firstOrNull { it.videoId == "UCignoreme" })
    }

    @Test
    fun `extracts the published relative time from lockup metadata`() {
        val body = """
            {"contents":[{"lockupViewModel":{
              "contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"dated000002",
              "metadata":{"lockupMetadataViewModel":{
                "title":{"content":"Dated lockup"},
                "metadata":{"contentMetadataViewModel":{"metadataRows":[
                  {"metadataParts":[{"text":{"content":"Some Channel"}}]},
                  {"metadataParts":[{"text":{"content":"1.2M views"}},{"text":{"content":"3 weeks ago"}}]}
                ]}}
              }}}}]}
        """.trimIndent()
        val video = (RelatedVideosParser.parse(body) as RelatedResult.Success).videos.single()
        assertEquals("3 weeks ago", video.publishedText)
    }

    @Test
    fun `a shorts lockup is collected and tagged SHORT`() {
        val body = """
            {"contents":[{"lockupViewModel":{
              "contentType":"LOCKUP_CONTENT_TYPE_SHORTS","contentId":"short000003",
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"A related Short"}}}}}]}
        """.trimIndent()
        val video = (RelatedVideosParser.parse(body) as RelatedResult.Success).videos.single()
        assertEquals("short000003", video.videoId)
        assertEquals(FeedVideo.Kind.SHORT, video.kind)
    }

    @Test
    fun `a live lockup is tagged LIVE`() {
        val body = """
            {"contents":[{"lockupViewModel":{
              "contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"live0000002",
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Live stream"}}},
              "contentImage":{"thumbnailViewModel":{"overlays":[
                {"thumbnailBottomOverlayViewModel":{"badges":[
                  {"thumbnailBadgeViewModel":{"badgeStyle":"THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE","text":"LIVE"}}
                ]}}
              ]}}}}]}
        """.trimIndent()
        val video = (RelatedVideosParser.parse(body) as RelatedResult.Success).videos.single()
        assertEquals(FeedVideo.Kind.LIVE, video.kind)
    }
}
