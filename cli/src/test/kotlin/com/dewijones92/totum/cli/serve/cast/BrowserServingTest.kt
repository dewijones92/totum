package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.browserAddressOf
import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.TargetDecks
import com.dewijones92.totum.cli.serve.targets.TargetSounds
import com.dewijones92.totum.data.targets.TargetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.nio.file.Files

class BrowserServingTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val screens = BrowserScreens(pollWaitMs = 0)
    private val streams = PiStreams(ProcessedSound(), "http://127.0.0.1:$port", port)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val decks = TargetDecks(
        Files.createTempDirectory("browser-decks").toFile(),
        scope,
        TargetSounds { _, _ -> null },
        listOf(BrowserSource(screens, streams)),
    )

    init {
        screens.onChanged = decks::sourcesChanged
        check(streams.serveScreens(screens.link)) { "the page should be served when there is an address" }
    }

    @After
    fun stop() = scope.cancel()

    private fun get(path: String): Pair<Int, String> =
        http.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute()
            .use { it.code to it.body.string() }

    @Test
    fun `the page is served on the cast port at the bare address, the real page, as the Pi's`() {
        val (code, body) = get("/")

        assertEquals(200, code)
        assertTrue(body.contains("data-host=\"pi\""))
        assertTrue(body.contains("shaka"))
    }

    @Test
    fun `a browser that opens the page is listed at once, not at the next once-a-minute look`() = runBlocking {
        assertTrue(decks.all().isEmpty())

        val (code, hello) = get("/screen/hello?name=Silk&id=fire1&codecs=AVC:1080")
        val listed = decks.all()

        assertEquals(200, code)
        assertTrue(hello, hello.contains("\"host\":\"pi\""))
        assertEquals(listOf(TargetKind.BROWSER), listed.map { it.target.kind })
        assertEquals("Silk", listed.single().target.label)
    }

    @Test
    fun `a browser that is renamed keeps its deck and its queue under the new name`() = runBlocking {
        get("/screen/hello?name=Silk&id=fire1")
        val first = decks.all().single()

        get("/screen/hello?name=Dave%27s%20TV&id=fire1")
        val second = decks.all().single()

        assertEquals(first.target.id, second.target.id)
        assertEquals("Dave's TV", second.target.label)
    }

    @Test
    fun `without an address nothing is served and no address is offered`() {
        val withoutBase = PiStreams(ProcessedSound(), null, port)

        assertEquals(false, withoutBase.serveScreens(screens.link))
        assertNull(browserAddressOf(null))
        assertNull(browserAddressOf("  "))
    }

    @Test
    fun `the address a person types is the cast address without its scheme`() {
        assertEquals("192.168.0.5:8771", browserAddressOf("http://192.168.0.5:8771/"))
        assertEquals("https://pi.example:8771", browserAddressOf("https://pi.example:8771"))
    }
}
