package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.playback.cast.HttpReply
import com.dewijones92.totum.playback.cast.HttpRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class BrowserSourceTest {

    private val screens = BrowserScreens({ "<html></html>".toByteArray() }, pollWaitMs = 0)

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun streams(base: String?) = PiStreams(ProcessedSound(), base, freePort())

    private fun hello(name: String, id: String) {
        screens.link.replyTo(HttpRequest("GET", "/screen/hello?name=$name&id=$id", emptyMap(), "192.168.0.40"))
            as HttpReply.Bytes
    }

    @Test
    fun `a browser that has opened the page is a target of its own kind, with an id that stays the same`() =
        runBlocking {
            hello("Silk", "screen-1-1731500000123")

            val found = BrowserSource(screens, streams("http://192.168.0.5:8771")).find(false)

            assertEquals(1, found.size)
            assertEquals(TargetKind.BROWSER, found.single().target.kind)
            assertEquals("Silk", found.single().target.label)
            assertEquals(BrowserSource.idOf("screen-1-1731500000123"), found.single().target.id)
            assertTrue(found.single().target.id.startsWith("browser-"))
        }

    @Test
    fun `the id is made of the page's own id, and only of letters and digits`() {
        assertEquals("browser-screen117315000", BrowserSource.idOf("screen-1-17315000"))
        assertTrue(BrowserSource.idOf("a/b c..d").matches(Regex("browser-[a-z0-9]+")))
        assertEquals(BrowserSource.idOf("Fire-1"), BrowserSource.idOf("fire-1"))
    }

    @Test
    fun `two browsers with one name are told apart by the end of their ids, one alone is not`() = runBlocking {
        hello("Silk", "tv-aaaa1111")
        hello("Silk", "tv-bbbb2222")
        hello("Edge", "pc-cccc3333")

        val labels = BrowserSource(screens, streams("http://192.168.0.5:8771")).find(false)
            .map { it.target.label }.sorted()

        assertEquals(listOf("Edge", "Silk ·1111", "Silk ·2222"), labels)
    }

    @Test
    fun `with no address the TVs can fetch from there is nothing to play to, so no browser is listed`() = runBlocking {
        hello("Silk", "tv-aaaa1111")

        assertTrue(BrowserSource(screens, streams(null)).find(false).isEmpty())
    }

    @Test
    fun `nobody listed until a browser has said hello`() = runBlocking {
        assertTrue(BrowserSource(screens, streams("http://192.168.0.5:8771")).find(false).isEmpty())
    }
}
