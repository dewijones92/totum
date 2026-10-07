package com.dewijones92.totum.data.sponsorblock

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.domain.SkipSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SponsorBlockSegmentSourceTest {

    @Test
    fun `parses segments, dropping malformed entries`() = runTest {
        val body = """
            [
              {"segment": [12.5, 45.0], "category": "sponsor"},
              {"segment": [100.0, 130.25], "category": "selfpromo"},
              {"segment": [50.0], "category": "broken"},
              {"category": "no-segment"},
              {"segment": [30.0, 20.0], "category": "inverted"}
            ]
        """.trimIndent()
        val source = SponsorBlockSegmentSource(fetcher = { FetchResult.Success(body) })

        val segments = source.segmentsFor("abc123")

        assertEquals(
            listOf(
                SkipSegment(12.5.seconds, 45.seconds),
                SkipSegment(100.seconds, 130.25.seconds),
            ),
            segments,
        )
    }

    @Test
    fun `requests the right video and categories`() = runTest {
        var requested: HttpUrl? = null
        val source = SponsorBlockSegmentSource(fetcher = { url ->
            requested = url
            FetchResult.Failure("HTTP 404")
        })

        source.segmentsFor("abc123")

        val url = checkNotNull(requested).value
        assertTrue(url.contains("videoID=abc123"))
        assertTrue(url.contains("category=sponsor"))
        assertTrue(url.contains("category=selfpromo"))
    }

    @Test
    fun `404, network failure, and garbage all fail open to no segments`() = runTest {
        assertTrue(SponsorBlockSegmentSource(fetcher = { FetchResult.Failure("HTTP 404") }).segmentsFor("x").isEmpty())
        assertTrue(SponsorBlockSegmentSource(fetcher = { FetchResult.Failure("timeout") }).segmentsFor("x").isEmpty())
        assertTrue(SponsorBlockSegmentSource(fetcher = { FetchResult.Success("<html>") }).segmentsFor("x").isEmpty())
    }

    /** Turning everything off means no request at all, not a request for nothing. */
    @Test
    fun `no enabled categories fetches nothing`() = runTest {
        var called = false
        val source = SponsorBlockSegmentSource(
            fetcher = {
                called = true
                FetchResult.Success("[]")
            },
            categories = { emptySet() },
        )

        assertTrue(source.segmentsFor("abc123").isEmpty())
        assertTrue("must not call SponsorBlock when nothing is enabled", !called)
    }

    @Test
    fun `only the enabled categories are asked for`() = runTest {
        var requested = ""
        val source = SponsorBlockSegmentSource(
            fetcher = { url ->
                requested = url.value
                FetchResult.Success("[]")
            },
            categories = { setOf(SkipCategory.INTRO, SkipCategory.OUTRO) },
        )

        source.segmentsFor("abc123")

        assertTrue("expected intro in $requested", "category=intro" in requested)
        assertTrue("expected outro in $requested", "category=outro" in requested)
        assertTrue("sponsor was not enabled: $requested", "category=sponsor" !in requested)
    }

    @Test
    fun `segments found are an answer`() = runTest {
        val body = """[{"segment": [12.5, 45.0], "category": "sponsor"}]"""
        val source = SponsorBlockSegmentSource(fetcher = { FetchResult.Success(body) })
        assertEquals(SegmentLookup.Answered(listOf(SkipSegment(12.5.seconds, 45.seconds))), source.lookup("abc123"))
    }

    @Test
    fun `a 404 is SponsorBlock answering that there are none`() = runTest {
        val source = SponsorBlockSegmentSource(fetcher = { FetchResult.Failure("HTTP 404", httpStatus = 404) })
        assertEquals(SegmentLookup.Answered(emptyList()), source.lookup("abc123"))
    }

    @Test
    fun `a failure to ask is not an answer, so nothing stored is overwritten`() = runTest {
        val offline = SponsorBlockSegmentSource(fetcher = { FetchResult.Failure("Unable to resolve host") })
        assertTrue(offline.lookup("abc123") is SegmentLookup.Unavailable)
        val broken = SponsorBlockSegmentSource(fetcher = { FetchResult.Failure("HTTP 503", httpStatus = 503) })
        assertTrue(broken.lookup("abc123") is SegmentLookup.Unavailable)
    }
}
