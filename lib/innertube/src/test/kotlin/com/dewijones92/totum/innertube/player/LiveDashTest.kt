package com.dewijones92.totum.innertube.player

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDashTest {

    private fun format(itag: Int, mime: String, height: Int?, bitrate: Long) = PlayableFormat(
        itag = itag,
        mimeType = mime,
        height = height,
        bitrate = bitrate,
        url = HttpUrl.of("https://rr1.googlevideo.com/videoplayback?itag=$itag&live=1"),
        initRange = 0L..739L,
        targetDurationSec = 5,
    )

    private val formats = listOf(
        format(299, "video/mp4; codecs=\"avc1.64002a\"", 1080, 6_000_000),
        format(248, "video/webm; codecs=\"vp9\"", 1080, 5_000_000),
        format(140, "audio/mp4; codecs=\"mp4a.40.2\"", null, 128_000),
        format(139, "audio/mp4; codecs=\"mp4a.40.5\"", null, 48_000),
    )

    @Test
    fun `the head comes from the response headers`() {
        val headers = mapOf("X-Head-Seqnum" to "2400", "X-Head-Time-Millis" to "12000000")
        assertEquals(LiveHead(2400, 12_000_000), LiveDash.head(headers::get))
        assertNull(LiveDash.head { null })
    }

    @Test
    fun `the manifest starts a few segments behind the head with each format's own segments`() {
        val mpd = LiveDash.manifest(formats, LiveHead(sequence = 2400, streamMs = 12_000_000))!!

        assertTrue(mpd, mpd.contains("""startNumber="2397""""))
        assertTrue(mpd, mpd.contains("""duration="5000000""""))
        assertTrue(mpd, mpd.contains("itag=299&amp;live=1&amp;sq=\$Number\$"))
        assertTrue(mpd, mpd.contains("""range="0-739""""))
        assertTrue("the best audio only", mpd.contains("itag=140") && !mpd.contains("itag=139"))
    }

    @Test
    fun `each container gets its own adaptation set`() {
        val mpd = LiveDash.manifest(formats, LiveHead(sequence = 2400, streamMs = 12_000_000))!!

        val setPattern = Regex(
            """<AdaptationSet id="\d+" contentType="video" mimeType="([^"]+)">(.*?)</AdaptationSet>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val representation = Regex("""<Representation id="(\d+)"""")
        val sets = setPattern.findAll(mpd).associate { set ->
            set.groupValues[1] to representation.findAll(set.groupValues[2]).map { it.groupValues[1] }.toList()
        }
        assertEquals(mapOf("video/mp4" to listOf("299"), "video/webm" to listOf("248")), sets)
    }

    @Test
    fun `the head probe asks with the smallest audio format`() {
        assertEquals(139, LiveDash.headProbeFormat(formats)?.itag)
    }
}
