package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.StreamingData
import org.junit.Assert.assertEquals
import org.junit.Test

class TheSignedInLadderRespectsTheDecoderTest {

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
            format(401, "video/mp4; codecs=\"av01.0.12M.10.0.110.09.16.09.0\"", 2160, 20_000_000),
            format(337, "video/webm; codecs=\"vp09.02.51.10.01.09.16.09.00\"", 2160, 18_000_000),
            format(137, "video/mp4; codecs=\"avc1.640028\"", 1080, 4_000_000),
            format(251, "audio/webm; codecs=\"opus\"", null, 160_000),
        ),
    )

    @Test
    fun `a codec the phone cannot decode never reaches the ladder`() {
        val noAv1 = VideoCodecSupport { codec, _, _ -> codec?.startsWith("av01") != true }
        val top = streams.videoQualities(support = noAv1).first()
        assertEquals(2160, top.height)
        assertEquals("https://x.test/videoplayback?itag=337", top.videoUrl.value)
    }

    @Test
    fun `a size the phone cannot decode drops the rung`() {
        val upTo1080 = VideoCodecSupport { _, _, height -> (height ?: 0) <= 1080 }
        assertEquals(listOf(1080), streams.videoQualities(support = upTo1080).map { it.height })
    }

    @Test
    fun `hardware beats bitrate when both decode`() {
        val hardwareAvcOnly = object : VideoCodecSupport {
            override fun canDecode(codec: String?, width: Int?, height: Int?) = true
            override fun isHardware(codec: String?, width: Int?, height: Int?) = codec?.startsWith("vp09") == true
        }
        assertEquals(
            "https://x.test/videoplayback?itag=337",
            streams.videoQualities(support = hardwareAvcOnly).first().videoUrl.value,
        )
    }
}
