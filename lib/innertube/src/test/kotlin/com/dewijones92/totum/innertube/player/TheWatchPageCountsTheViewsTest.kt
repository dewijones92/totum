package com.dewijones92.totum.innertube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TheWatchPageCountsTheViewsTest {

    private fun page(views: String) = """
        {"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
          {"videoPrimaryInfoRenderer":{"viewCount":{"videoViewCountRenderer":$views},
            "dateText":{"simpleText":"Nov 23, 2026"}}},
          {"videoSecondaryInfoRenderer":{"attributedDescription":{"content":"About it"}}}
        ]}}}}}
    """.trimIndent()

    @Test
    fun `the short view count is read as YouTube wrote it`() {
        val facts = WatchNextParser.facts(
            page("""{"viewCount":{"simpleText":"24,123 views"},"shortViewCount":{"simpleText":"24K views"}}"""),
        )

        assertEquals("24K views", facts?.viewsText)
        assertEquals("About it", facts?.description)
        assertEquals(java.time.LocalDate.of(2026, 11, 23), facts?.publishedOn)
    }

    @Test
    fun `with only the long count it is shortened the way every list says it`() {
        val facts = WatchNextParser.facts(page("""{"viewCount":{"simpleText":"1,234,567 views"}}"""))

        assertEquals("1.2M views", facts?.viewsText)
    }

    @Test
    fun `a page with no view count says none rather than zero`() {
        assertNull(WatchNextParser.facts(page("""{"viewCount":{"simpleText":"No views"}}"""))?.viewsText)
        assertNull(WatchNextParser.facts("""{"contents":{}}""")?.viewsText)
    }

    @Test
    fun `a description of a played video carries its views`() {
        val body = """
            {"contents":{"singleColumnWatchNextResults":{"results":{"results":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"videoMetadataRenderer":{"videoId":"abc","title":{"runs":[{"text":"24 Hours"}]}}}]}},
              {"videoPrimaryInfoRenderer":{"viewCount":{"videoViewCountRenderer":
                {"shortViewCount":{"simpleText":"3.1M views"}}}}}
            ]}}}}}
        """.trimIndent()

        assertEquals("3.1M views", WatchNextParser.details(body, "abc")?.viewsText)
    }

    @Test
    fun `a player response carries the exact count from its video details, else the watch page's`() {
        val withCount = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abc","title":"T","viewCount":"107000"},
             "streamingData":{"adaptiveFormats":[]}}
        """.trimIndent()
        val bare = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abc","title":"T"},
             "streamingData":{"adaptiveFormats":[]}}
        """.trimIndent()
        val described = WatchNextDetails("T", null, null, viewsText = "9K views")

        assertEquals("107K views", detailsOf(withCount, described).viewsText)
        assertEquals("9K views", detailsOf(bare, described).viewsText)
        assertNull(detailsOf(bare, null).viewsText)
    }

    private fun detailsOf(body: String, described: WatchNextDetails?): PlayerDetails =
        ((PlayerResponseParser.parse(body, described)) as PlayerResult.Success).details!!
}
