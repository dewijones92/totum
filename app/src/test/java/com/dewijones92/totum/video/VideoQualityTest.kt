package com.dewijones92.totum.video

import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quality ladder must only offer streams the device can decode. Selecting a
 * quality that no decoder can handle looked like "above 1080p doesn't play".
 */
class VideoQualityTest {

    private fun video(height: Int, codec: String, muxed: Boolean = false) = MediaFormat(
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

    private val audio = MediaFormat(
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

    private fun metadata(vararg formats: MediaFormat) = MediaMetadata(
        id = "v",
        title = "v",
        uploader = null,
        durationSeconds = 60,
        thumbnailUrl = null,
        formats = formats.toList(),
    )

    @Test
    fun `a height whose only codec is undecodable is not offered`() {
        // The reported bug: 2160p exists only as AV1, the device can't decode AV1,
        // and the app offered it anyway — so choosing it stopped playback.
        val meta = metadata(video(1080, "avc1.640028", muxed = true), video(2160, "av01.0.12M.08"), audio)
        val noAv1 = VideoCodecSupport { codec, _, _ -> codec?.startsWith("av01") != true }

        val heights = meta.videoQualities(noAv1).map { it.height }

        assertEquals(listOf(1080), heights)
    }

    @Test
    fun `a height with a decodable alternative codec is still offered`() {
        val meta = metadata(video(2160, "av01.0.12M.08"), video(2160, "vp09.00.50.08"), audio)
        val noAv1 = VideoCodecSupport { codec, _, _ -> codec?.startsWith("av01") != true }

        val qualities = meta.videoQualities(noAv1)

        assertEquals(listOf(2160), qualities.map { it.height })
        assertTrue("must use the decodable stream", qualities.single().videoUrl.value.contains("vp09"))
    }

    @Test
    fun `where several codecs decode, the more hardware-friendly one wins`() {
        val meta = metadata(video(1440, "av01.0.12M.08"), video(1440, "vp09.00.50.08"), audio)

        val quality = meta.videoQualities(VideoCodecSupport.Permissive).single()

        assertTrue("VP9 preferred over AV1", quality.videoUrl.value.contains("vp09"))
    }

    @Test
    fun `size matters, not just the codec`() {
        // Plenty of devices decode VP9 at 1080p but not at 2160p.
        val meta = metadata(video(1080, "vp09.00.50.08"), video(2160, "vp09.00.50.08"), audio)
        val upTo1080 = VideoCodecSupport { _, _, height -> (height ?: 0) <= 1080 }

        assertEquals(listOf(1080), meta.videoQualities(upTo1080).map { it.height })
    }

    @Test
    fun `a video-only height with no audio to merge is not offered`() {
        val meta = metadata(video(2160, "vp09.00.50.08"))

        assertTrue(meta.videoQualities(VideoCodecSupport.Permissive).isEmpty())
    }

    @Test
    fun `qualities are highest first`() {
        val meta = metadata(video(720, "avc1", muxed = true), video(1080, "avc1", muxed = true), audio)

        assertEquals(listOf(1080, 720), meta.videoQualities(VideoCodecSupport.Permissive).map { it.height })
    }

    @Test
    fun `a height that exists only as a dub is not offered`() {
        // Report 0.1.373: 1080p existed only as an automatic German dub, the ladder offered it,
        // and the auto-pick takes the tallest — so the fix to the picker alone changed nothing.
        val meta = metadata(dubbed(1080), original(720), englishAudio)

        val heights = meta.videoQualities(VideoCodecSupport.Permissive, WANT_ENGLISH).map { it.height }

        assertEquals(listOf(720), heights)
    }

    @Test
    fun `that height comes back once you ask for that language`() {
        // The ladder is the ladder for the track you are listening to.
        val meta = metadata(dubbed(1080), original(720), englishAudio)

        val heights = meta.videoQualities(VideoCodecSupport.Permissive, listOf("de")).map { it.height }

        assertEquals(listOf(1080), heights)
    }

    @Test
    fun `a video with no track in your language still offers every height`() {
        // Nothing better exists, so dropping heights would leave an unwatchable video.
        val meta = metadata(dubbed(1080), dubbed(720))

        val heights = meta.videoQualities(VideoCodecSupport.Permissive, WANT_ENGLISH).map { it.height }

        assertEquals(listOf(1080, 720), heights)
    }

    @Test
    fun `a video-only height merges with the audio in the language you asked for`() {
        val meta = metadata(video(2160, "vp09.00.50.08"), germanAudio, englishAudio)

        val quality = meta.videoQualities(VideoCodecSupport.Permissive, WANT_ENGLISH).single()

        assertEquals("https://example.com/audio-en", quality.audioUrl?.value)
        assertEquals("en-US", quality.audio.languageCode)
    }

    /** A muxed stream labelled only by its URL — what YouTube's HLS manifest actually gives. */
    private fun hls(height: Int, xtags: String) = MediaFormat(
        formatId = "hls-$height-$xtags",
        container = "mp4",
        width = height * 16 / 9,
        height = height,
        hasVideo = true,
        hasAudio = true,
        fileSizeBytes = null,
        url = "https://manifest.googlevideo.com/hls/itag/96/sgoap/xtags%3D${xtags.replace("=", "%3D")}/index.m3u8",
        videoCodec = "avc1.640028",
        audioCodec = "mp4a.40.2",
    )

    private fun dubbed(height: Int) = hls(height, "acont=dubbed-auto:lang=de-DE")
    private fun original(height: Int) = hls(height, "acont=original:lang=en-US")

    private val englishAudio = audio.copy(
        formatId = "audio-en",
        url = "https://example.com/audio-en",
        language = "en-US",
        languagePreference = 10,
    )

    private val germanAudio = audio.copy(
        formatId = "audio-de",
        url = "https://example.com/audio-de",
        language = "de-DE",
        languagePreference = -1,
        fileSizeBytes = 9_000,
    )

    private companion object {
        val WANT_ENGLISH = listOf("en")
    }
}
