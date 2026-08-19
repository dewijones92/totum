package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerDetails
import com.dewijones92.totum.innertube.player.StreamingData
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SABR refuses a LIVE stream for the reason that is actually true, and takes a VOD that is merely
 * missing a `lastModified`.
 *
 * Two separate things were tangled together. `bestAudio` and `bestVideo` required `lastModified`, and a
 * live stream carries none on any format — measured 2026-08-19, all 13 formats came back
 * `lastModified=false` — so live fell out of the SABR path by accident, through a gate aimed at
 * something else. Relaxing that gate let live in, and live then **failed**: measured the same evening,
 * SABR served the bytes quite happily (`fetch #1 itag 135 -> 902900B response, 820060B kept`) and
 * ExoPlayer never became ready, because a live stream joins mid-broadcast and its media arrives with no
 * initialization segment:
 *
 * ```
 * VOD  : run 0 itag=135 init=true  startBytes=0    length=2249   <- the moov
 *        run 1 itag=135 init=false startBytes=2249 seq=1
 * LIVE : run 1 itag=135 init=false startBytes=0    seq=2770558   <- no init anywhere
 * ```
 *
 * The init data IS being sent, in `FORMAT_INITIALIZATION_METADATA(42)`, which `SabrStream` ignores
 * along with `LIVE_METADATA(31)` and `SABR_SEEK(45)`. Until those are handled, a live stream must be
 * refused BY NAME so it falls back to extraction and keeps playing — an accidental refusal that later
 * stops being accidental is how a working feature disappears.
 */
class ALiveStreamIsNotRefusedBySabrTest {

    @Test
    fun `a VOD missing lastModified is still resolved`() {
        val resolved = SabrResolve.prepare(VIDEO_ID, streamingData(), details(lengthSeconds = 600))

        assertNotNull(
            "a VOD whose formats happen to carry no lastModified was refused — that gate was aimed at " +
                "identifying formats, not at excluding anything, and SABR fetches them fine",
            resolved,
        )
        assertNotNull("it must still offer a picture, not only sound", resolved!!.videoUrl)
    }

    @Test
    fun `a live stream is refused, so it falls back to something that plays`() {
        val resolved = SabrResolve.prepare(VIDEO_ID, streamingData(), details(lengthSeconds = null))

        assertNull(
            "SABR accepted a live stream. It can fetch the bytes but not play them: live media arrives " +
                "with no initialization segment, so the player never becomes ready. Refusing sends it to " +
                "extraction, which does play.",
            resolved,
        )
    }

    /** The shape a real live stream came back with: no lastModified, no xtags, no contentLength. */
    private fun streamingData() = StreamingData(
        formats = listOf(
            PlayableFormat(
                itag = 135,
                mimeType = "video/mp4; codecs=\"avc1.4d401f\"",
                height = 480,
                bitrate = 1_000_000,
                url = null,
                lastModified = null,
                fps = 30,
            ),
            PlayableFormat(
                itag = 140,
                mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                height = null,
                bitrate = 144_000,
                url = null,
                lastModified = null,
            ),
        ),
        serverAbrStreamingUrl = HttpUrl.of("https://sabr.test/videoplayback"),
        ustreamerConfig = byteArrayOf(1, 2, 3),
    )

    /** A live stream has no length, which is how one is told apart from a VOD. */
    private fun details(lengthSeconds: Long?) = PlayerDetails(
        videoId = VIDEO_ID,
        title = "A stream",
        author = null,
        channelId = null,
        lengthSeconds = lengthSeconds,
        thumbnailUrl = null,
        description = null,
    )

    private companion object {
        const val VIDEO_ID = "YDvsBbKfLPA"
    }
}
