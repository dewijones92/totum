package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.data.home.HomeSeverity
import com.dewijones92.totum.polite.Politeness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HomeHealthYouTubeTest {
    private var now = 1_000_000_000_000L
    private val gate = PoliteGate(now = { now })
    private val botBackOff = BotBackOff { now }
    private val polite = Politeness(gate, botBackOff)
    private val health = HomeHealth(
        HomeHealth.Probes({ 200_000_000_000L }, { HomeHealth.AiState.SIGNED_IN }, youTube = polite),
        clock = { now },
    )

    private fun problems(signedIn: Boolean = true) =
        runBlocking { health.problems(emptyList(), youTubeSignedIn = signedIn) }

    private fun clock(epochMs: Long) = DateTimeFormatter.ofPattern("HH:mm z")
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private fun appString(name: String): String {
        val xml = File("../app/src/main/res/values/strings.xml").readText()
        return Regex("""<string name="$name">([^<]*)</string>""").find(xml)!!.groupValues[1]
    }

    @Test
    fun `a quiet YouTube is no problem`() {
        assertEquals(emptyList<String>(), problems().map { it.key })
    }

    @Test
    fun `a 429 slow-down is a warning that says when every call may go again, and clears`() {
        gate.tooManyRequests("www.youtube.com/youtubei/v1/player", retryAfterMs = 120_000)
        val until = gate.slowDown.value!!.untilMs

        val found = problems().single()

        assertEquals("youtube-slow-down", found.key)
        assertEquals(HomeSeverity.WARNING, found.severity)
        assertTrue(found.text, found.text.startsWith(appString("polite_slow_down_title")))
        assertTrue(found.text, "waits until ${clock(until)}" in found.text)
        assertTrue(found.text, "HTTP 429 on www.youtube.com/youtubei/v1/player" in found.text)
        now = until + 1
        assertEquals(emptyList<String>(), problems().map { it.key })
    }

    @Test
    fun `a bot check while the Pi is signed in is a warning that anonymous YouTube waits`() {
        botBackOff.refused("yt-dlp extract dQw4w9WgXcQ")
        val until = botBackOff.until.value!!

        val found = problems(signedIn = true).single()

        assertEquals("youtube-bot", found.key)
        assertEquals(HomeSeverity.WARNING, found.severity)
        assertTrue(found.text, found.text.startsWith(appString("polite_bot_title")))
        assertTrue(found.text, "anonymous YouTube" in found.text && clock(until) in found.text)
    }

    @Test
    fun `a bot check while the Pi is signed out is broken, because nothing will play`() {
        botBackOff.refused("yt-dlp extract dQw4w9WgXcQ")

        val found = problems(signedIn = false).single()

        assertEquals("youtube-bot", found.key)
        assertEquals(HomeSeverity.BROKEN, found.severity)
        assertTrue(found.text, found.text.startsWith(appString("polite_bot_signed_out_title")))
        assertTrue(found.text, "signed in" in found.text)
    }

    @Test
    fun `a bot check ends with its back-off`() {
        botBackOff.refused("yt-dlp extract dQw4w9WgXcQ")
        now = botBackOff.until.value!! + 1

        assertEquals(emptyList<String>(), problems().map { it.key })
    }

    @Test
    fun `both at once are two problems, slow-down first as in the app`() {
        gate.tooManyRequests("x", retryAfterMs = null)
        botBackOff.refused("y")

        assertEquals(listOf("youtube-slow-down", "youtube-bot"), problems().map { it.key })
    }
}
