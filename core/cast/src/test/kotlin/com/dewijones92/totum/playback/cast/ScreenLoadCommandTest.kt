package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLoadCommandTest {

    private val link = "https://totum.home.test/cast-out/${"ab".repeat(32)}/manifest.mpd"

    private fun command(direct: Boolean) = loadCommand(
        ScreenLoad(
            load = 3,
            stream = RoutedStream(link, direct),
            positionMs = 12_000,
            play = true,
            rate = 1.5f,
            title = "A \"film\"",
            subs = "en",
        ),
    )

    @Test
    fun `a stream that has its own origin says so, and a phone stream does not`() {
        assertTrue(command(direct = true), command(direct = true).contains("\"direct\":true"))
        assertFalse(command(direct = false), command(direct = false).contains("direct"))
    }

    @Test
    fun `the load carries what the page needs, escaped`() {
        val text = command(direct = false)

        assertTrue(text, text.startsWith("""{"do":"load","load":3,"url":"https://totum.home.test/cast-out/"""))
        assertTrue(text, text.contains(""""pos":12000,"play":true,"rate":1.5,"title":"A \"film\"","subs":"en"}"""))
    }

    @Test
    fun `no captions is a JSON null, not the word null in quotes`() {
        val text = loadCommand(ScreenLoad(1, RoutedStream(link, direct = true), 0, false, 1f, "t", null))

        assertTrue(text, text.endsWith(""""subs":null,"direct":true}"""))
    }

    @Test
    fun `the bundled page uses a direct address as it is and rewrites any other onto its own origin`() {
        val page = String(ScreenPage.bundled())

        assertTrue("the page ignores a direct stream", page.contains("command.direct"))
        assertTrue("the page must still rewrite a phone address", page.contains("location.origin + given.pathname"))
    }
}
