package com.dewijones92.totum.video

import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quality ladder must prefer a durable stream too — not only the audio picker.
 *
 * `MediaMetadata.isDurable` was added to `bestAudioFormat` on 2026-08-18 and that fixed listening,
 * which is most of how Dewi uses the app. **It did not fix watching**, because the ladder is a
 * different picker and knows nothing about it. Seeking an hour into a 97-minute video failed three
 * times in a row on the emulator with:
 *
 * ```
 * reached 3600000ms and then stopped — the seek landed but the stream is not being served
 * playback: re-resolving after Rejected (attempt 1 of 1) from 3600000ms
 * ```
 *
 * while the audio path served byte 61,567,041 of the same video quite happily. The resolve line said
 * which stream it had chosen — `format 96-18`, an HLS manifest — and the ladder had picked it on codec
 * preference alone.
 *
 * This is the third time in two days that one question turned out to have several answers in several
 * places: five pickers for "which stream" (2026-08-09), two for "which stream will play next"
 * (2026-08-17), and now two for "which stream is fetchable". Fixing one and declaring victory is the
 * repeatable mistake, so the rule belongs to every picker or to none.
 */
class TheLadderPrefersDurableStreamsTest {

    private fun video(itag: Int, height: Int, url: String, muxed: Boolean = false) = MediaFormat(
        formatId = "$itag",
        container = "mp4",
        width = height * 16 / 9,
        height = height,
        hasVideo = true,
        hasAudio = muxed,
        fileSizeBytes = height.toLong() * 1000,
        url = url,
        videoCodec = "avc1.640028",
        audioCodec = if (muxed) "mp4a.40.2" else null,
    )

    private fun audio(itag: Int, url: String) = MediaFormat(
        formatId = "$itag",
        container = "m4a",
        width = null,
        height = null,
        hasVideo = false,
        hasAudio = true,
        fileSizeBytes = 1_000_000,
        url = url,
        videoCodec = null,
        audioCodec = "mp4a.40.2",
    )

    private fun metadata(vararg formats: MediaFormat) = MediaMetadata(
        id = "uSMGENDH_QI",
        title = "Cosmic Dawn",
        uploader = null,
        durationSeconds = 5805,
        thumbnailUrl = null,
        formats = formats.toList(),
    )

    /**
     * THE case. Two streams at the same height and codec: one capped, one solved. The ladder used to
     * take whichever the codec sort happened to leave first.
     */
    @Test
    fun `at one height the durable stream is the one offered`() {
        val meta = metadata(
            video(137, 1080, "$HOST?itag=137&c=ANDROID_VR"),
            video(299, 1080, "$HOST?itag=299&n=solved"),
            audio(140, "$HOST?itag=140&n=solved"),
        )

        val chosen = meta.videoQualities(VideoCodecSupport.Permissive).single { it.height == 1080 }
        assertTrue(
            "the ladder offered a capped stream at 1080p: ${chosen.videoUrl.value}",
            "n=solved" in chosen.videoUrl.value,
        )
    }

    /** The audio half of a merge has to be durable too, or the picture plays and the sound stops. */
    @Test
    fun `the merged audio is a durable one`() {
        val meta = metadata(
            video(299, 1080, "$HOST?itag=299&n=solved"),
            audio(140, "$HOST?itag=140&c=ANDROID_VR"),
            audio(251, "$HOST?itag=251&n=solved"),
        )

        val chosen = meta.videoQualities(VideoCodecSupport.Permissive).single { it.height == 1080 }
        assertTrue(
            "the merge partner was a capped stream: ${chosen.audioUrl?.value}",
            chosen.audioUrl?.value?.contains("n=solved") == true,
        )
    }

    /** With nothing durable on offer the ladder still offers something — half a video beats none. */
    @Test
    fun `where nothing is durable a height is still offered`() {
        val meta = metadata(
            video(137, 1080, "$HOST?itag=137&c=ANDROID_VR", muxed = true),
        )

        assertTrue(meta.videoQualities(VideoCodecSupport.Permissive).any { it.height == 1080 })
    }

    /**
     * 4K, and the trade-off durability creates: it now outranks the HARDWARE-codec preference.
     *
     * Worth pinning deliberately rather than discovering. Undecodable codecs are still filtered out
     * entirely (`canDecode`), so nothing unplayable is ever offered — but among codecs the device CAN
     * decode, the hardware-friendly one used to win and now a durable software one beats it. That is
     * the right way round: a hardware stream refused after its first megabyte plays for a minute, and a
     * software one plays to the end. It is a judgement though, and if 4K software decode ever proves
     * worse than a minute of hardware, this is the test to change and the reason to change it.
     */
    @Test
    fun `at 4K a durable stream beats a hardware-friendlier capped one`() {
        val meta = metadata(
            video(401, 2160, "$HOST?itag=401&c=ANDROID_VR"),
            video(315, 2160, "$HOST?itag=315&n=solved"),
            audio(140, "$HOST?itag=140&n=solved"),
        )

        val chosen = meta.videoQualities(VideoCodecSupport.Permissive).single { it.height == 2160 }
        assertTrue(
            "4K offered a capped stream: ${chosen.videoUrl.value}",
            "n=solved" in chosen.videoUrl.value,
        )
    }

    /** And nothing undecodable is offered, durable or not — that filter still comes first. */
    @Test
    fun `a durable stream in a codec the device cannot decode is still not offered`() {
        val meta = metadata(
            video(401, 2160, "$HOST?itag=401&n=solved"),
            video(137, 1080, "$HOST?itag=137&c=ANDROID_VR", muxed = true),
            audio(140, "$HOST?itag=140&n=solved"),
        )
        val noneAt2160 = VideoCodecSupport { _, _, height -> (height ?: 0) < 2160 }

        val heights = meta.videoQualities(noneAt2160).map { it.height }
        assertTrue("an undecodable 4K stream must not be offered just because it is durable", 2160 !in heights)
    }

    /** The full ladder survives: every height the device can decode is still on offer. */
    @Test
    fun `a range of resolutions is still offered`() {
        val meta = metadata(
            video(160, 144, "$HOST?itag=160&n=a"),
            video(133, 240, "$HOST?itag=133&n=b"),
            video(134, 360, "$HOST?itag=134&n=c"),
            video(135, 480, "$HOST?itag=135&n=d"),
            video(136, 720, "$HOST?itag=136&n=e"),
            video(137, 1080, "$HOST?itag=137&n=f"),
            video(315, 2160, "$HOST?itag=315&n=g"),
            audio(140, "$HOST?itag=140&n=h"),
        )

        assertEquals(
            listOf(2160, 1080, 720, 480, 360, 240, 144),
            meta.videoQualities(VideoCodecSupport.Permissive).map { it.height },
        )
    }

    private companion object {
        const val HOST = "https://rr1---sn-abc.googlevideo.com/videoplayback"
    }
}
