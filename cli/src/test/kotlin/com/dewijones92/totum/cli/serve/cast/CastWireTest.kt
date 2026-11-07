package com.dewijones92.totum.cli.serve.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastWireTest {

    @Test
    fun `a load carries the address, the type, the title and where to start`() {
        val line = CastWire.load("https://x/y.mp4", "video/mp4", "Peppa", 42_000)

        assertEquals(
            """{"cmd":"load","url":"https://x/y.mp4","type":"video/mp4","title":"Peppa","startMs":42000}""",
            line,
        )
    }

    @Test
    fun `the plain commands are one line each`() {
        assertEquals("""{"cmd":"pause"}""", CastWire.pause(true))
        assertEquals("""{"cmd":"resume"}""", CastWire.pause(false))
        assertEquals("""{"cmd":"seek","positionMs":5000}""", CastWire.seek(5_000))
        assertEquals("""{"cmd":"volume","percent":3}""", CastWire.volume(3))
        assertEquals("""{"cmd":"stop"}""", CastWire.stop())
    }

    @Test
    fun `what the helper says is read back as events, and nonsense is ignored`() {
        assertEquals(
            CastEvent.Ready("LG TV", "webOS"),
            CastWire.parse("""{"event":"ready","name":"LG TV","model":"webOS"}"""),
        )
        assertEquals(
            CastEvent.Status("PLAYING", null, 12_000, 60_000),
            CastWire.parse(
                """{"event":"status","state":"PLAYING","idle":null,"positionMs":12000,"durationMs":60000}"""
            ),
        )
        assertEquals(
            CastEvent.Status("IDLE", "FINISHED", 60_000, null),
            CastWire.parse(
                """{"event":"status","state":"IDLE","idle":"FINISHED","positionMs":60000,"durationMs":null}"""
            ),
        )
        assertEquals(
            CastEvent.Failure("no Chromecast"),
            CastWire.parse("""{"event":"error","message":"no Chromecast"}""")
        )
        assertEquals(
            CastEvent.Status("IDLE", "CANCELLED", 0, null, contentId = "https://old"),
            CastWire.parse(
                """{"event":"status","state":"IDLE","idle":"CANCELLED","positionMs":0,"durationMs":null,"contentId":"https://old"}"""
            ),
        )
        assertNull(CastWire.parse("not json"))
        assertNull(CastWire.parse("""{"event":"mystery"}"""))
    }

    @Test
    fun `a playing reading moves on with the clock and a paused one stands still`() {
        val playing = CastReading(10_000, 60_000, playing = true, atMs = 1_000)
        val paused = CastReading(10_000, 60_000, playing = false, atMs = 1_000)

        assertEquals(14_000L, playing.positionAt(5_000))
        assertEquals(10_000L, paused.positionAt(5_000))
        assertEquals(10_000L, playing.positionAt(0))
    }

    @Test
    fun `the type comes from the address's own mime, else its extension, else the picture`() {
        assertEquals(
            "video/webm",
            contentTypeOf("https://g/v?mime=video%2Fwebm&x=1", picture = true, processed = false)
        )
        assertEquals("audio/mpeg", contentTypeOf("https://x/a.mp3", picture = false, processed = false))
        assertEquals("video/x-matroska", contentTypeOf("/media/film.mkv", picture = true, processed = false))
        assertEquals("video/mp4", contentTypeOf("https://x/unknown", picture = true, processed = false))
        assertEquals("audio/mpeg", contentTypeOf("https://x/unknown", picture = false, processed = false))
        assertEquals("audio/aac", contentTypeOf("https://x/s/abc.aac", picture = false, processed = true))
    }

    @Test
    fun `configured casts are read as name equals host pairs`() {
        val casts = knownCastsFrom("LG TV=192.168.0.30; Chromecast Ultra = 192.168.0.31 ;bad;=x;y=")

        assertEquals(
            listOf("LG TV" to "192.168.0.30", "Chromecast Ultra" to "192.168.0.31"),
            casts.map { it.name to it.host }
        )
        assertEquals(emptyList<KnownCasts>(), knownCastsFrom(null))
    }
}
