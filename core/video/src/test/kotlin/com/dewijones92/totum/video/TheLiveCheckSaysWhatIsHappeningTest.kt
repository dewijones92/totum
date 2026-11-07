package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.player.LiveCheck
import com.dewijones92.totum.innertube.player.PlayerClient
import com.dewijones92.totum.innertube.player.PlayerDetails
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TheLiveCheckSaysWhatIsHappeningTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private val states = LiveStates { 0L }

    private fun streams(account: InnerTubePlayerStreams.AccountPlayer? = null) = InnerTubePlayerStreams(
        InnerTubeClient(client = OkHttpClient(), playerUrl = server.url("/player").toString()),
        account = account,
        liveStates = states,
    )

    private fun reply(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    private val liveFormat = """{"itag":95,"mimeType":"video/mp4","height":720,"bitrate":1,
        "url":"https://x.test/videoplayback?itag=95&live=1&sq=1"}"""

    private fun playable(isLiveContent: Boolean, hls: Boolean, live: Boolean) = """
        {"playabilityStatus":{"status":"OK"},
         "streamingData":{"formats":[${if (live) liveFormat else
            """{"itag":18,"mimeType":"video/mp4","height":360,"bitrate":1,"url":"https://x.test/videoplayback?itag=18"}"""}]
            ${if (hls) ""","hlsManifestUrl":"https://x.test/live.m3u8"""" else ""}},
         "videoDetails":{"videoId":"dQw4w9WgXcQ","title":"A stream","isLiveContent":$isLiveContent}}
    """.trimIndent()

    private fun details(live: Boolean) = PlayerDetails(
        videoId = "dQw4w9WgXcQ",
        title = "A stream",
        author = null,
        channelId = null,
        lengthSeconds = null,
        thumbnailUrl = null,
        description = null,
        isLive = live,
    )

    @Test
    fun `a stream that is on right now is live`() = runTest {
        reply(playable(isLiveContent = true, hls = true, live = true))

        val check = streams().liveCheck("dQw4w9WgXcQ")

        assertEquals(LiveCheck.Live("the anonymous player"), check)
    }

    @Test
    fun `a stream that has finished, still carrying the live flag but no live address, has ended`() = runTest {
        reply(playable(isLiveContent = true, hls = false, live = false))

        assertEquals(LiveCheck.Ended("the anonymous player"), streams().liveCheck("dQw4w9WgXcQ"))
    }

    @Test
    fun `an ordinary video that answers plays is reported as ended and never as live`() = runTest {
        reply(playable(isLiveContent = false, hls = false, live = false))

        assertEquals(LiveCheck.Ended("the anonymous player"), streams().liveCheck("dQw4w9WgXcQ"))
    }

    @Test
    fun `a stream that has not started yet is still upcoming and the registry is told`() = runTest {
        reply(
            """{"playabilityStatus":{"status":"LIVE_STREAM_OFFLINE","reason":"Live event will begin in 2 hours"},
                "videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Soon"}}""",
        )

        val check = streams().liveCheck("dQw4w9WgXcQ")

        assertTrue(check is LiveCheck.StillUpcoming)
        assertEquals(LivePhase.UPCOMING, states.of("dQw4w9WgXcQ")?.phase)
    }

    @Test
    fun `an answer that says nothing about being live is unknown with the reason, not ended`() = runTest {
        reply("""{"playabilityStatus":{"status":"ERROR","reason":"This video is unavailable"}}""")

        val check = streams().liveCheck("dQw4w9WgXcQ")

        assertTrue(check.toString(), check is LiveCheck.Unknown)
        assertNull(states.of("dQw4w9WgXcQ"))
    }

    @Test
    fun `a signed-in answer is taken before the anonymous player is asked`() = runTest {
        val account = InnerTubePlayerStreams.AccountPlayer { _, _ ->
            PlayerResult.Success(
                streaming = StreamingData(
                    formats = emptyList(),
                    liveManifestUrl = HttpUrl.of("https://x.test/live.m3u8"),
                ),
                details = details(live = true),
                client = PlayerClient.ANDROID,
            )
        }

        val check = streams(account).liveCheck("dQw4w9WgXcQ")

        assertEquals(LiveCheck.Live("the signed-in TV client"), check)
        assertEquals("the anonymous player was not asked", 0, server.requestCount)
    }

    @Test
    fun `the kind a resolve reads from a response is live only with a manifest or live segments`() {
        val manifest = StreamingData(
            formats = emptyList(),
            liveManifestUrl = HttpUrl.of("https://x.test/live.m3u8"),
        )
        val plain = StreamingData(formats = emptyList())
        val liveDetails = details(live = true)
        val vodDetails = details(live = false)

        assertEquals(MediaContentKind.LIVE, contentKindOf(liveDetails, manifest))
        assertEquals(MediaContentKind.WAS_LIVE, contentKindOf(liveDetails, plain))
        assertEquals(MediaContentKind.STANDARD, contentKindOf(vodDetails, manifest))
        assertEquals(MediaContentKind.STANDARD, contentKindOf(null, manifest))
    }
}
