package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class TheQualityMenuNamesTheCodecTest {

    private fun ytdlpVideo(height: Int, codec: String?, muxed: Boolean = false) = MediaFormat(
        formatId = "$codec-$height",
        container = "mp4",
        width = height * 16 / 9,
        height = height,
        hasVideo = true,
        hasAudio = muxed,
        fileSizeBytes = height.toLong(),
        url = "https://example.com/$codec-$height",
        videoCodec = codec,
        audioCodec = if (muxed) "mp4a.40.2" else null,
    )

    private val ytdlpAudio = MediaFormat(
        formatId = "audio",
        container = "m4a",
        width = null,
        height = null,
        hasVideo = false,
        hasAudio = true,
        fileSizeBytes = 1_000,
        url = "https://example.com/audio",
        videoCodec = null,
        audioCodec = "mp4a.40.2",
    )

    private fun metadata(vararg formats: MediaFormat) = MediaMetadata("v", "v", null, 60, null, formats.toList())

    private fun format(itag: Int, mime: String, height: Int?, bitrate: Long) = PlayableFormat(
        itag = itag,
        mimeType = mime,
        height = height,
        width = height?.let { it * 16 / 9 },
        bitrate = bitrate,
        url = HttpUrl.of("https://x.test/videoplayback?itag=$itag"),
    )

    private val opus = format(251, "audio/webm; codecs=\"opus\"", null, 160_000)

    @Test
    fun `a yt-dlp ladder names the codec of the stream each height uses`() {
        val labels = metadata(
            ytdlpVideo(2160, "av01.0.12M.08"),
            ytdlpVideo(1080, "vp09.00.40.08"),
            ytdlpVideo(720, "avc1.64001F", muxed = true),
            ytdlpAudio,
        ).videoQualities(VideoCodecSupport.Permissive).map { it.label }

        assertEquals(listOf("2160p · AV1", "1080p · VP9", "720p · AVC"), labels)
    }

    @Test
    fun `where two codecs decode the label names the one that wins`() {
        val quality = metadata(
            ytdlpVideo(1440, "av01.0.12M.08"),
            ytdlpVideo(1440, "vp09.00.50.08"),
            ytdlpAudio,
        ).videoQualities(VideoCodecSupport.Permissive).single()

        assertEquals("1440p · VP9", quality.label)
    }

    @Test
    fun `a stream with no codec named is labelled by its height alone`() {
        val quality = metadata(ytdlpVideo(480, null, muxed = true))
            .videoQualities(VideoCodecSupport.Permissive).single()

        assertEquals("480p", quality.label)
    }

    @Test
    fun `a codec the labeller does not know is labelled by its height alone`() {
        val quality = metadata(ytdlpVideo(480, "mystery.1", muxed = true))
            .videoQualities(VideoCodecSupport.Permissive).single()

        assertEquals("480p", quality.label)
    }

    @Test
    fun `the quality id stays the plain height so a saved choice survives the new label`() {
        val quality = metadata(ytdlpVideo(1080, "vp09.00.40.08"), ytdlpAudio)
            .videoQualities(VideoCodecSupport.Permissive).single()

        assertEquals("1080", quality.id)
        assertEquals(1080, quality.height)
    }

    @Test
    fun `a signed-in ladder names the codec of the winning stream at each height`() {
        val streams = StreamingData(
            formats = listOf(
                format(401, "video/mp4; codecs=\"av01.0.12M.10.0.110.09.16.09.0\"", 2160, 20_000_000),
                format(337, "video/webm; codecs=\"vp09.02.51.10.01.09.16.09.00\"", 2160, 18_000_000),
                format(137, "video/mp4; codecs=\"avc1.640028\"", 1080, 4_000_000),
                opus,
            ),
        )

        val permissive = streams.videoQualities().map { it.label }
        val noVp9Support = VideoCodecSupport { codec, _, _ -> codec?.startsWith("vp09") != true }
        val noVp9 = streams.videoQualities(support = noVp9Support).map { it.label }

        assertEquals(listOf("2160p · VP9", "1080p · AVC"), permissive)
        assertEquals(listOf("2160p · AV1", "1080p · AVC"), noVp9)
    }

    @Test
    fun `a live stream keeps its own label and carries no codec`() {
        val live = StreamingData(
            formats = listOf(format(137, "video/mp4; codecs=\"avc1.640028\"", 1080, 4_000_000)),
            liveManifestUrl = HttpUrl.of("https://x.test/live.m3u8"),
        )

        assertEquals(listOf("🔴 Live"), live.videoQualities().map { it.label })
    }
}
