package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Breadcrumbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLinkTest {

    private var now = 1_000L
    private val reports = mutableListOf<Pair<String, ScreenReport>>()
    private val link = ScreenLink(
        "k3y",
        { "<html>receiver</html>".toByteArray() },
        { now },
        pollWaitMs = 0
    ).also { link ->
        link.onReport = { id, report -> reports += id to report }
    }

    private fun get(path: String) = link.replyTo(
        HttpRequest("GET", path, emptyMap(), "192.168.0.20")
    ) as HttpReply.Bytes

    private fun HttpReply.Bytes.text() = String(body)

    @Test
    fun `the page is served without a key, and every call needs it`() {
        assertTrue(get("/screen").text().contains("receiver"))
        assertEquals(Http.CLIENT_ERROR, get("/screen/hello?key=wrong&name=PC").status)
    }

    @Test
    fun `a screen that says hello is present, and drops away once it stops being heard`() {
        val hello = get("/screen/hello?key=k3y&name=Dewi%27s%20PC&id=pc1").text()

        assertTrue(hello, hello.contains("\"id\":\"pc1\""))
        assertEquals(listOf("Dewi's PC"), link.present().map { it.name })
        now += 61_000
        assertEquals(emptyList<Screen>(), link.present())
    }

    @Test
    fun `commands reach the screen in order, and only the ones it has not had`() {
        get("/screen/hello?key=k3y&name=PC&id=pc1")
        link.send("pc1", """{"do":"load","url":"http://phone/c/t/manifest.mpd","pos":0}""")
        val second = link.send("pc1", """{"do":"pause"}""")

        val both = get("/screen/poll?key=k3y&id=pc1&after=0").text()
        val none = get("/screen/poll?key=k3y&id=pc1&after=$second").text()

        assertTrue(both, both.indexOf("\"load\"") in 0 until both.indexOf("\"pause\""))
        assertEquals("[]", none)
    }

    @Test
    fun `a state report reaches the player`() {
        get("/screen/hello?key=k3y&name=PC&id=pc1")

        assertEquals(
            Http.OK,
            get("/screen/state?key=k3y&id=pc1&pos=61500&playing=1&buffering=0&ended=0&load=7").status
        )

        assertEquals("pc1", reports.single().first)
        assertEquals(
            ScreenReport(61_500, playing = true, buffering = false, ended = false, error = null, atMs = now, load = 7),
            reports.single().second
        )
    }

    @Test
    fun `every state reply says what the phone is playing and whether it is playing here`() {
        get("/screen/hello?key=k3y&name=PC&id=pc1")
        link.nowPlaying = NowPlaying("Peep Show \"Jeremy's Mum\"", "Channel 4", playing = true, onScreen = "pc1")

        val here = get("/screen/state?key=k3y&id=pc1&pos=0&playing=1").text()
        val elsewhere = get("/screen/state?key=k3y&id=pc2&pos=0&playing=0").text()

        assertTrue(here, here.contains("\"title\":\"Peep Show \\\"Jeremy's Mum\\\"\""))
        assertTrue(here, here.contains("\"here\":true"))
        assertTrue(elsewhere, elsewhere.contains("\"here\":false"))
    }

    @Test
    fun `with nothing on the phone the reply says so`() {
        val hello = get("/screen/hello?key=k3y&name=PC&id=pc1").text()
        assertTrue(hello, hello.contains("\"now\":null"))
    }

    @Test
    fun `Watch here asks the phone to play on that screen`() {
        val asked = mutableListOf<String>()
        link.onWatch = { id -> asked += id }
        get("/screen/hello?key=k3y&name=PC&id=pc1")

        assertEquals(Http.OK, get("/screen/watch?key=k3y&id=pc1").status)
        assertEquals(listOf("pc1"), asked)
    }

    @Test
    fun `Watch here from a screen the phone has not heard from is refused`() {
        val asked = mutableListOf<String>()
        link.onWatch = { id -> asked += id }

        assertEquals(Http.NOT_FOUND, get("/screen/watch?key=k3y&id=stranger").status)
        assertEquals(Http.CLIENT_ERROR, get("/screen/watch?key=wrong&id=pc1").status)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the page's seek bar asks the phone to seek that screen to a position`() {
        val asked = mutableListOf<Pair<String, Long>>()
        link.onSeek = { id, positionMs -> asked += id to positionMs }
        get("/screen/hello?key=k3y&name=PC&id=pc1")

        assertEquals(Http.OK, get("/screen/seek?key=k3y&id=pc1&pos=93000").status)
        assertEquals(listOf("pc1" to 93_000L), asked)
    }

    @Test
    fun `a seek for an id nobody has says exactly what a seek for a real one says, and nothing seeks`() {
        val asked = mutableListOf<Pair<String, Long>>()
        link.onSeek = { id, positionMs -> asked += id to positionMs }
        get("/screen/hello?key=k3y&name=PC&id=pc1")

        val real = get("/screen/seek?key=k3y&id=pc1&pos=5")
        val stranger = get("/screen/seek?key=k3y&id=stranger&pos=5")

        assertEquals(real.status, stranger.status)
        assertEquals(real.text(), stranger.text())
        assertEquals(listOf("pc1" to 5L), asked)
    }

    @Test
    fun `a seek from a stranger, with the wrong key or with no usable position is refused and nothing seeks`() {
        val asked = mutableListOf<Pair<String, Long>>()
        link.onSeek = { id, positionMs -> asked += id to positionMs }
        get("/screen/hello?key=k3y&name=PC&id=pc1")

        assertEquals(Http.CLIENT_ERROR, get("/screen/seek?key=wrong&id=pc1&pos=5").status)
        assertEquals(Http.CLIENT_ERROR, get("/screen/seek?key=k3y&id=pc1&pos=soon").status)
        assertEquals(Http.CLIENT_ERROR, get("/screen/seek?key=k3y&id=pc1&pos=-4").status)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `nothing outside the screen paths is answered here`() {
        assertEquals(null, link.replyTo(HttpRequest("GET", "/c/token/manifest.mpd", emptyMap(), "x")))
    }

    @Test
    fun `an id the server makes cannot be guessed from the one before`() {
        val first = Regex("\"id\":\"([^\"]+)\"").find(get("/screen/hello?key=k3y&name=PC").text())!!.groupValues[1]
        val second = Regex("\"id\":\"([^\"]+)\"").find(get("/screen/hello?key=k3y&name=PC").text())!!.groupValues[1]

        assertNotEquals(first, second)
        assertTrue(first, Regex("screen-[0-9a-f]{32}").matches(first))
        assertTrue(second, Regex("screen-[0-9a-f]{32}").matches(second))
    }

    @Test
    fun `two tabs with one id, the newer wins and the older is told it was replaced`() {
        get("/screen/hello?key=k3y&name=PC&id=pc1&tab=old")
        get("/screen/hello?key=k3y&name=PC&id=pc1&tab=new")

        val older = get("/screen/state?key=k3y&id=pc1&tab=old&pos=1000&playing=1&load=1")
        val polled = get("/screen/poll?key=k3y&id=pc1&tab=old&after=0")
        val newer = get("/screen/state?key=k3y&id=pc1&tab=new&pos=2000&playing=1&load=1")

        assertEquals(409, older.status)
        assertTrue(older.text(), older.text().contains("\"replaced\":true"))
        assertEquals(409, polled.status)
        assertEquals(Http.OK, newer.status)
        assertEquals(listOf(2_000L), reports.map { it.second.positionMs })
    }

    @Test
    fun `an older tab waiting on a poll is let go the moment a newer tab says hello`() {
        val slow = ScreenLink("k3y", { ByteArray(0) }, { now }, pollWaitMs = 5_000)
        fun hit(path: String) = slow.replyTo(HttpRequest("GET", path, emptyMap(), "x")) as HttpReply.Bytes
        hit("/screen/hello?key=k3y&name=PC&id=pc1&tab=old")
        var answer: HttpReply.Bytes? = null
        val waiting = Thread { answer = hit("/screen/poll?key=k3y&id=pc1&tab=old&after=0") }.also { it.start() }
        Thread.sleep(200)

        hit("/screen/hello?key=k3y&name=PC&id=pc1&tab=new")
        waiting.join(2_000)

        assertFalse("the old tab was still waiting", waiting.isAlive)
        assertEquals(409, answer?.status)
    }

    @Test
    fun `calls with no tab, as from a page that does not send one, still work`() {
        get("/screen/hello?key=k3y&name=PC&id=pc1&tab=a")

        assertEquals(Http.OK, get("/screen/state?key=k3y&id=pc1&pos=1&playing=1&load=1").status)
    }

    @Test
    fun `a goodbye from the current tab makes the screen unheard at once, one from a replaced tab does not`() {
        get("/screen/hello?key=k3y&name=PC&id=pc1&tab=old")
        get("/screen/hello?key=k3y&name=PC&id=pc1&tab=new")

        get("/screen/bye?key=k3y&id=pc1&tab=old")
        assertTrue(link.heardWithin("pc1", 60_000))

        get("/screen/bye?key=k3y&id=pc1&tab=new")
        assertFalse(link.heardWithin("pc1", 60_000))
        assertTrue("still listed, as a lost screen is, so its deck can say it was closed", link.present().isNotEmpty())
        assertTrue((link.screen("pc1")?.byeAtMs ?: 0L) > 0L)
    }

    @Test
    fun `a screen that says hello again after a goodbye is back`() {
        get("/screen/hello?key=k3y&name=PC&id=pc1&tab=a")
        get("/screen/bye?key=k3y&id=pc1&tab=a")

        get("/screen/hello?key=k3y&name=PC&id=pc1&tab=b")

        assertTrue(link.heardWithin("pc1", 60_000))
        assertEquals(0L, link.screen("pc1")?.byeAtMs)
    }

    @Test
    fun `a screen unheard for long enough is forgotten with its commands, one heard lately is kept`() {
        get("/screen/hello?key=k3y&name=Gone&id=gone1")
        get("/screen/hello?key=k3y&name=Here&id=here1")
        link.send("gone1", """{"do":"pause"}""")
        now += 9 * 60_000
        get("/screen/state?key=k3y&id=here1&pos=0&playing=0")
        now += 2 * 60_000

        val forgotten = link.evictGone(10 * 60_000L)

        assertEquals(listOf("gone1"), forgotten)
        assertEquals(null, link.screen("gone1"))
        assertEquals("Here", link.screen("here1")?.name)
    }

    @Test
    fun `a poll from an id the server forgot is told to say hello again`() {
        assertEquals(Http.CLIENT_ERROR, get("/screen/poll?key=k3y&id=unknown&after=0").status)
    }

    @Test
    fun `the log never carries a command's address past its question mark`() {
        Breadcrumbs.clear()
        get("/screen/hello?key=k3y&name=PC&id=pc1")

        link.send(
            "pc1",
            """{"do":"load","url":"http://jf:8096/Videos/a/stream?static=true&api_key=SECRETKEY","pos":0}""",
        )

        val logged = Breadcrumbs.snapshot().joinToString("\n") { it.message }
        assertTrue(logged, logged.contains("http://jf:8096/Videos/a/stream"))
        assertFalse(logged, logged.contains("SECRETKEY"))
    }
}
