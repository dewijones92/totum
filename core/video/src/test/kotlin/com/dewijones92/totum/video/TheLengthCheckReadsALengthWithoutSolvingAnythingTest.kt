package com.dewijones92.totum.video

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.innertube.browse.InnerTubeClient
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

class TheLengthCheckReadsALengthWithoutSolvingAnythingTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private val states = LiveStates { 0L }
    private val backOff = BotBackOff()
    private var solved = 0

    private fun streams() = InnerTubePlayerStreams(
        InnerTubeClient(client = OkHttpClient(), playerUrl = server.url("/player").toString()),
        solveN = { data -> data.also { solved++ } },
        botBackOff = backOff,
        liveStates = states,
    )

    private fun reply(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    private fun playable(length: String?, isLiveContent: Boolean = false, live: Boolean = false): String {
        val liveBits = if (live) "&live=1&sq=1" else ""
        val hls = if (live) ""","hlsManifestUrl":"https://x.test/live.m3u8"""" else ""
        val seconds = if (length != null) ""","lengthSeconds":"$length"""" else ""
        return """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"formats":[{"itag":18,"mimeType":"video/mp4","height":360,"bitrate":1,
               "url":"https://x.test/videoplayback?itag=18$liveBits"}]$hls},
             "videoDetails":{"videoId":"abc","title":"A video"$seconds,"isLiveContent":$isLiveContent}}
        """.trimIndent()
    }

    @Test
    fun `an ordinary video says its length and no URL is solved for it`() = runTest {
        reply(playable("5400"))

        val check = streams().lengthCheck("abc")

        assertEquals(LengthCheck.Known(5400, wasLive = false, via = "the anonymous player"), check)
        assertEquals("solving n is for playing, never for a length", 0, solved)
    }

    @Test
    fun `a stream that has ended reports its length and says it was live`() = runTest {
        reply(playable("7200", isLiveContent = true))

        val check = streams().lengthCheck("abc")

        assertEquals(LengthCheck.Known(7200, wasLive = true, via = "the anonymous player"), check)
    }

    @Test
    fun `a stream that is on now has no length to report`() = runTest {
        reply(playable(length = null, isLiveContent = true, live = true))

        assertEquals(LengthCheck.LiveNow("the anonymous player"), streams().lengthCheck("abc"))
    }

    @Test
    fun `a stream that has not begun is upcoming`() = runTest {
        reply(
            """{"playabilityStatus":{"status":"LIVE_STREAM_OFFLINE","reason":"Live event will begin in 2 hours"},
                "videoDetails":{"videoId":"abc","title":"Soon"}}""",
        )

        assertEquals(LengthCheck.Upcoming("the anonymous player"), streams().lengthCheck("abc"))
    }

    @Test
    fun `a refused video that still names its length gives it`() = runTest {
        reply(
            """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"},
                "videoDetails":{"videoId":"abc","title":"Rated","lengthSeconds":"2400"}}""",
        )

        val check = streams().lengthCheck("abc")

        assertEquals(LengthCheck.Known(2400, wasLive = false, via = "the anonymous player"), check)
    }

    @Test
    fun `an unavailable video says it has no length`() = runTest {
        reply("""{"playabilityStatus":{"status":"ERROR","reason":"This video is unavailable"}}""")

        val check = streams().lengthCheck("abc")

        assertTrue(check.toString(), check is LengthCheck.SaysNone)
    }

    @Test
    fun `a bot check is unknown, not a verdict on the video, and backs anonymous YouTube off`() = runTest {
        reply("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}""")

        val check = streams().lengthCheck("abc")

        assertTrue(check.toString(), check is LengthCheck.Unknown)
        assertTrue(backOff.until.value != null)
    }

    @Test
    fun `while backing off nothing is asked and the answer is unknown`() = runTest {
        backOff.refused("an earlier video")

        val check = streams().lengthCheck("abc")

        assertTrue(check.toString(), check is LengthCheck.Unknown)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a failed request is unknown`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())

        assertTrue(streams().lengthCheck("abc") is LengthCheck.Unknown)
    }

    @Test
    fun `a video that was never live leaves the live registry alone`() = runTest {
        reply(playable("5400"))

        streams().lengthCheck("abc")

        assertNull(states.of("abc"))
    }
}
