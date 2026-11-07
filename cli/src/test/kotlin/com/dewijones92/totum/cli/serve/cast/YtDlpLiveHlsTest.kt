package com.dewijones92.totum.cli.serve.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpLiveHlsTest {

    private val asked = mutableListOf<List<String>>()

    private fun hls(
        output: CommandOutput = CommandOutput(
            0,
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/x/index.m3u8\n",
            ""
        ),
        jsRuntime: String? = "node:/usr/bin/node",
    ) = YtDlpLiveHls("/opt/venv/bin/python", jsRuntime) { command ->
        asked += command
        output
    }

    @Test
    fun `it asks yt-dlp for the audio the way the speaker service that worked did`() {
        val url = hls().urlFor("Dx5qFachd3A")

        assertEquals("https://manifest.googlevideo.com/api/manifest/hls_playlist/x/index.m3u8", url)
        val command = asked.single()
        assertEquals(listOf("/opt/venv/bin/python", "-m", "yt_dlp"), command.take(3))
        assertTrue(command.toString(), command.containsAll(listOf("-g", "--no-warnings")))
        assertEquals("bestaudio/worst", command[command.indexOf("-f") + 1])
        assertEquals(
            "youtube:player_client=web_embedded",
            command[command.indexOf("--extractor-args") + 1],
        )
        assertEquals("node:/usr/bin/node", command[command.indexOf("--js-runtimes") + 1])
        assertEquals("https://www.youtube.com/watch?v=Dx5qFachd3A", command.last())
    }

    @Test
    fun `with no JavaScript runtime configured none is named`() {
        hls(jsRuntime = null).urlFor("Dx5qFachd3A")

        assertFalse(asked.single().contains("--js-runtimes"))
    }

    @Test
    fun `a failure is no address, and the reason is not lost`() {
        val failing = hls(CommandOutput(1, "", "ERROR: [youtube] Dx5qFachd3A: Sign in to confirm you're not a bot"))

        assertNull(failing.urlFor("Dx5qFachd3A"))
        assertTrue(failing.lastFailure.orEmpty().contains("not a bot"))
    }

    @Test
    fun `output that is not an address is no address`() {
        assertNull(hls(CommandOutput(0, "something odd\n", "")).urlFor("Dx5qFachd3A"))
    }

    @Test
    fun `the first address is taken when yt-dlp prints several`() {
        val two = CommandOutput(0, "https://a.test/one.m3u8\nhttps://b.test/two.m3u8\n", "")

        assertEquals("https://a.test/one.m3u8", hls(two).urlFor("Dx5qFachd3A"))
    }

    @Test
    fun `an id that is not a YouTube id never reaches the command line`() {
        assertNull(hls().urlFor("--exec=rm -rf /"))
        assertNull(hls().urlFor("short"))
        assertTrue(asked.isEmpty())
    }
}
