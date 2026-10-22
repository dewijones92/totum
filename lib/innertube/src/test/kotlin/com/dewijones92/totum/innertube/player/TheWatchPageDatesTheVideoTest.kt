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
}
