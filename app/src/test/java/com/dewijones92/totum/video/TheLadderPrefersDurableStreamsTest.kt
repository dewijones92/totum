package com.dewijones92.totum.video

import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
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

    private companion object {
        const val HOST = "https://rr1---sn-abc.googlevideo.com/videoplayback"
    }
}
