package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.StreamingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TheSignedInLadderKeepsTheOtherCodecsTest {

    private fun format(itag: Int, mime: String, height: Int?, bitrate: Long) = PlayableFormat(
        itag = itag,
        mimeType = mime,
        height = height,
        width = height?.let { it * 16 / 9 },
        bitrate = bitrate,
        url = HttpUrl.of("https://x.test/videoplayback?itag=$itag"),
    )

    private val streams = StreamingData(
        formats = listOf(
            format(248, "video/webm; codecs=\"vp09.00.40.08\"", 1080, 2_000_000),
            format(137, "video/mp4; codecs=\"avc1.640028\"", 1080, 4_000_000),
            format(247, "video/webm; codecs=\"vp09.00.31.08\"", 720, 1_000_000),
            format(251, "audio/webm; codecs=\"opus\"", null, 160_000),
        ),
    )

    private fun families(top: VideoQuality) = (listOf(top) + top.alternates).map { VideoCodecFamily.of(it.codec) }

    @Test
    fun `a height holds the other codec as an alternate with the sound to merge it with`() {
        val top = streams.videoQualities().first { it.height == 1080 }

        assertEquals(setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC), families(top).toSet())
        assertEquals(1, top.alternates.size)
        assertNotNull(top.alternates.single().audioUrl)
        assertEquals(1080, top.alternates.single().height)
    }

    @Test
    fun `a height with one codec has no alternates`() {
        assertTrue(streams.videoQualities().first { it.height == 720 }.alternates.isEmpty())
    }

    @Test
    fun `for a model nobody has measured the H264 stream leads and VP9 is the alternate`() {
        val generic = CastAwareCodecSupport(VideoCodecSupport.Permissive) { CastScreen.of("Chromecast") }

        val top = streams.videoQualities(support = generic).first { it.height == 1080 }

        assertEquals(VideoCodecFamily.AVC, VideoCodecFamily.of(top.codec))
        assertEquals(listOf(VideoCodecFamily.VP9), top.alternates.map { VideoCodecFamily.of(it.codec) })
    }

    @Test
    fun `a codec the screen cannot decode is not an alternate`() {
        val noAvc = VideoCodecSupport { codec, _, _ -> codec?.startsWith("avc") != true }

        assertTrue(streams.videoQualities(support = noAvc).first { it.height == 1080 }.alternates.isEmpty())
    }
}
