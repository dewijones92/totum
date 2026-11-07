package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.player.LiveCheck
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerDetails
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.innertube.player.UpcomingLive
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TheResolverTellsTheLiveRegistryTest {

    private val id = "dQw4w9WgXcQ"
    private val url = HttpUrl.of("https://www.youtube.com/watch?v=$id")
    private val source = SourceId("s")
    private val states = LiveStates { 0L }

    private class StandardEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl) = ExtractionResult.Success(
            MediaMetadata(
                id = "dQw4w9WgXcQ",
                title = "From extraction",
                uploader = null,
                durationSeconds = 10,
                thumbnailUrl = null,
                formats = listOf(
                    MediaFormat("18", "mp4", 640, 360, true, true, null, "https://x.test/e", "avc1", "mp4a"),
                ),
            ),
        )
    }

    private fun response(live: Boolean, manifest: Boolean) = PlayerResult.Success(
        streaming = StreamingData(
            formats = listOf(
                PlayableFormat(
                    itag = 18,
                    mimeType = "video/mp4; codecs=\"avc1, mp4a\"",
                    height = 360,
                    bitrate = 1_000,
                    url = HttpUrl.of("https://x.test/p"),
                ),
            ),
            liveManifestUrl = HttpUrl.of("https://x.test/live.m3u8").takeIf { manifest },
        ),
        details = PlayerDetails(
            videoId = "dQw4w9WgXcQ",
            title = "From the player",
            author = null,
            channelId = null,
            lengthSeconds = 10,
            thumbnailUrl = null,
            description = null,
            isLive = live,
        ),
    )

    private fun resolver(
        engine: YtDlpEngine = StandardEngine(),
        signedIn: PlayerResult.Success? = null,
        check: LiveCheck = LiveCheck.Unknown("not asked"),
    ) = VideoResolver(
        engine = engine,
        skipSegments = SkipSegmentSource { emptyList() },
        playerStreams = object : PlayerStreams {
            override suspend fun playerFor(videoId: String): PlayerResult.Success? = null
            override suspend fun signedInFor(videoId: String, describe: Boolean) = signedIn
            override suspend fun liveCheck(videoId: String) = check
        },
        liveStates = states,
    )

    @Test
    fun `a resolve of a live stream puts it in the registry as live`() = runTest {
        resolver(signedIn = response(live = true, manifest = true)).resolve(url, source, asked = "play")

        assertEquals(LivePhase.LIVE, states.of(id)?.phase)
    }

    @Test
    fun `a resolve of a finished stream puts it in the registry as ended`() = runTest {
        resolver(signedIn = response(live = true, manifest = false)).resolve(url, source, asked = "play")

        assertEquals(LivePhase.ENDED, states.of(id)?.phase)
    }

    @Test
    fun `a resolve of an ordinary video leaves the registry empty`() = runTest {
        resolver().resolve(url, source, asked = "play")

        assertNull(states.of(id))
    }

    @Test
    fun `a yt-dlp resolve never turns a live entry into an ended one`() = runTest {
        states.noteLive(id, "test")

        val resolved = resolver().resolve(url, source, asked = "play")

        assertEquals(MediaContentKind.STANDARD, resolved?.item?.contentKind)
        assertEquals(LivePhase.LIVE, states.of(id)?.phase)
    }

    @Test
    fun `a plain resolve clears an upcoming entry because the video is playable now`() = runTest {
        states.noteUpcoming(id, UpcomingLive(null, "soon"), "test")

        resolver().resolve(url, source, asked = "play")

        assertNull(states.of(id))
    }

    @Test
    fun `the live check records what it found`() = runTest {
        assertEquals(LiveCheck.Live("x"), resolver(check = LiveCheck.Live("x")).liveCheck(url))
        assertEquals(LivePhase.LIVE, states.of(id)?.phase)

        assertEquals(LiveCheck.Ended("y"), resolver(check = LiveCheck.Ended("y")).liveCheck(url))
        assertEquals(LivePhase.ENDED, states.of(id)?.phase)
    }

    @Test
    fun `a live check that could not tell, or finds it still upcoming, changes nothing`() = runTest {
        states.noteLive(id, "test")

        resolver(check = LiveCheck.Unknown("no answer")).liveCheck(url)
        assertEquals(LivePhase.LIVE, states.of(id)?.phase)

        resolver(check = LiveCheck.StillUpcoming(UpcomingLive(null, "soon"), "via")).liveCheck(url)
        assertEquals(LivePhase.LIVE, states.of(id)?.phase)
    }

    @Test
    fun `an address that is not a YouTube watch link is unknown and asks nobody`() = runTest {
        val check = resolver(check = LiveCheck.Live("x")).liveCheck(HttpUrl.of("https://example.com/a.mp3"))

        assertEquals(LiveCheck.Unknown("not a YouTube watch link"), check)
    }
}
