package com.dewijones92.totum.innertube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TheWatchPageDatesTheVideoTest {

    private fun page(dateText: String) = """
        {"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
          {"videoPrimaryInfoRenderer":{"title":{"runs":[{"text":"AI NEWS"}]},
            "dateText":{"simpleText":"$dateText"},
            "relativeDateText":{"simpleText":"6 days ago"}}}
        ]}}}}}
    """.trimIndent()

    @Test
    fun `the watch page's date is read, whatever it is prefixed with`() {
        assertEquals(LocalDate.of(2026, 10, 15), WatchNextParser.publishedOn(page("Oct 15, 2026")))
        assertEquals(LocalDate.of(2026, 10, 15), WatchNextParser.publishedOn(page("Premiered Oct 15, 2026")))
        assertEquals(LocalDate.of(2026, 3, 2), WatchNextParser.publishedOn(page("Streamed live on Mar 2, 2026")))
        assertEquals(LocalDate.of(2026, 10, 15), WatchNextParser.publishedOn(page("15 Oct 2026")))
    }

    @Test
    fun `no date, or one that will not parse, is no date`() {
        assertNull(WatchNextParser.publishedOn("""{"contents":{}}"""))
        assertNull(WatchNextParser.publishedOn(page("6 days ago")))
    }

    @Test
    fun `a watch page that describes a video dates it too`() {
        val page = """
            {"contents":{"singleColumnWatchNextResults":{"results":{"results":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"videoMetadataRenderer":{"videoId":"abc","title":{"runs":[{"text":"24 Hours"}]},
                  "dateText":{"runs":[{"text":"Oct 31, 2026"}]}}}]}},
              {"videoPrimaryInfoRenderer":{"dateText":{"simpleText":"Oct 31, 2026"}}}
            ]}}}}}
        """.trimIndent()

        assertEquals(LocalDate.of(2026, 10, 31), WatchNextParser.details(page, "abc")?.publishedOn)
    }

    @Test
    fun `a player response is dated by its microformat, else by the watch page that described it`() {
        val withMicroformat = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abc","title":"T"},
             "microformat":{"playerMicroformatRenderer":{"publishDate":"2026-10-31T03:33:41-07:00"}},
             "streamingData":{"adaptiveFormats":[]}}
        """.trimIndent()
        val bare = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abc"},"streamingData":{"adaptiveFormats":[]}}
        """.trimIndent()
        val described = WatchNextDetails("T", null, null, publishedOn = LocalDate.of(2026, 10, 30))

        assertEquals(LocalDate.of(2026, 10, 31), detailsOf(withMicroformat, described).publishedOn)
        assertEquals(LocalDate.of(2026, 10, 30), detailsOf(bare, described).publishedOn)
        assertNull(
            detailsOf(bare.replace("\"videoId\":\"abc\"", "\"videoId\":\"abc\",\"title\":\"T\""), null).publishedOn
        )
    }

    @Test
    fun `the watch page's relative wording and the microformat's exact time are kept`() {
        val page = """
            {"contents":{"singleColumnWatchNextResults":{"results":{"results":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"videoMetadataRenderer":{"videoId":"abc","title":{"runs":[{"text":"24 Hours"}]},
                  "dateText":{"runs":[{"text":"1 hour ago"}]}}},
                {"videoDescriptionHeaderRenderer":{"publishDate":{"simpleText":"Published on Oct 31, 2026"}}}]}}
            ]}}}}}
        """.trimIndent()
        val withMicroformat = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abc","title":"T"},
             "microformat":{"playerMicroformatRenderer":{"publishDate":"2026-10-31T03:33:41-07:00"}},
             "streamingData":{"adaptiveFormats":[]}}
        """.trimIndent()

        val described = WatchNextParser.details(page, "abc")

        assertEquals("1 hour ago", described?.publishedAgo)
        assertEquals(LocalDate.of(2026, 10, 31), described?.publishedOn)
        assertEquals(java.time.Instant.parse("2026-10-31T10:33:41Z"), detailsOf(withMicroformat, null).publishedAt)
    }

    private fun detailsOf(body: String, described: WatchNextDetails?): PlayerDetails =
        (PlayerResponseParser.parse(body, described) as? PlayerResult.Success)?.details ?: error("not parsed")
}
