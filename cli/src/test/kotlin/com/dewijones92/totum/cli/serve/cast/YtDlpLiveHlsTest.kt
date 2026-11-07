package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.PoliteGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpLiveHlsTest {

    private val asked = mutableListOf<List<String>>()
    private val inFlightWhenAsked = mutableListOf<Int>()
    private var now = NOW_MS
    private val gate = PoliteGate(maxInFlight = 1, perSecond = 1_000.0, burst = 1_000, now = { now })
    private val botBackOff = BotBackOff { now }
    private val plain = "https://manifest.googlevideo.com/api/manifest/hls_playlist/x/index.m3u8\n"

    private fun hls(
        output: CommandOutput = CommandOutput(0, plain, ""),
        jsRuntime: String? = "node:/usr/bin/node",
    ) = YtDlpLiveHls("/opt/venv/bin/python", jsRuntime, gate, botBackOff, { now }) { command ->
        asked += command
        inFlightWhenAsked += gate.inFlight
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

    @Test
    fun `the lookup takes a permit from the YouTube gate, and gives it back`() {
        hls().urlFor("Dx5qFachd3A")

        assertEquals("the call ran holding the gate's one permit", listOf(1), inFlightWhenAsked)
        assertEquals(0, gate.inFlight)
    }

    @Test
    fun `an answer is kept per video, so playing the same station again does not ask yt-dlp again`() {
        val lookup = hls()

        lookup.urlFor("Dx5qFachd3A")
        now += 5 * 60_000
        val again = lookup.urlFor("Dx5qFachd3A")

        assertEquals(plain.trim(), again)
        assertEquals(1, asked.size)
        lookup.urlFor("AAAAAAAAAAA")
        assertEquals("another video is asked for", 2, asked.size)
    }

    @Test
    fun `an answer is kept until ten minutes before the address's own expiry`() {
        val expires = NOW_MS / 1_000 + 3_600
        val url = "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/$expires/id/x/index.m3u8"
        val lookup = hls(CommandOutput(0, "$url\n", ""))

        lookup.urlFor("Dx5qFachd3A")
        now += 49 * 60_000
        lookup.urlFor("Dx5qFachd3A")
        assertEquals("still good 49 minutes on", 1, asked.size)

        now += 2 * 60_000
        lookup.urlFor("Dx5qFachd3A")
        assertEquals("51 minutes on is within ten of the expiry, so asked again", 2, asked.size)
    }

    @Test
    fun `an address with no expiry in it is kept for half an hour`() {
        val lookup = hls()

        lookup.urlFor("Dx5qFachd3A")
        now += 29 * 60_000
        lookup.urlFor("Dx5qFachd3A")
        assertEquals(1, asked.size)

        now += 2 * 60_000
        lookup.urlFor("Dx5qFachd3A")
        assertEquals(2, asked.size)
    }

    @Test
    fun `a failure is not kept, so the next play tries again`() {
        val lookup = hls(CommandOutput(1, "", "ERROR: boom"))

        lookup.urlFor("Dx5qFachd3A")
        lookup.urlFor("Dx5qFachd3A")

        assertEquals(2, asked.size)
    }

    @Test
    fun `a bot check backs anonymous YouTube off, and the next lookup does not call it at all`() {
        val lookup = hls(CommandOutput(1, "", "ERROR: Sign in to confirm you're not a bot"))

        lookup.urlFor("Dx5qFachd3A")
        val second = lookup.urlFor("AAAAAAAAAAA")

        assertNull(second)
        assertEquals("the second lookup was not made", 1, asked.size)
        assertTrue(lookup.lastFailure.orEmpty().contains("backing off"))
        assertTrue(botBackOff.until.value != null)
    }

    @Test
    fun `a 429 slows the whole gate down`() {
        val lookup = hls(CommandOutput(1, "", "ERROR: HTTP Error 429: Too Many Requests"))

        lookup.urlFor("Dx5qFachd3A")

        assertTrue(gate.slowDown.value != null)
    }

    private companion object {
        const val NOW_MS = 1_800_000_000_000L
    }
}
