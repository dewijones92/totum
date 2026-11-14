package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.playback.cast.CastFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KodiControlTest {

    private val kodi = FakeKodi()
    private var now = 100_000L
    private val ended = mutableListOf<Boolean>()
    private val lost = mutableListOf<String>()
    private val logged = mutableListOf<String>()

    @After
    fun tidy() {
        kodi.close()
        Diag.sink = Diag.Sink { _, _, _, _ -> }
    }

    private fun control(
        dash: Boolean = false,
        fake: FakeKodi = kodi,
        file: java.io.File? = fake.credentialsFile(),
    ) = KodiControl(
        fake.address,
        "Kodi",
        JdkKodiRpc(kodiEndpoint(fake.address), KodiCredentials.fromFile(file)),
        clock = { now },
        polls = false,
        dashWhenAble = dash,
    ).also {
        it.onEnded = { failed -> ended += failed }
        it.onLost = { reason -> lost += reason }
    }

    private fun ready(c: KodiControl = control()): KodiControl = c.also { assertTrue(runBlocking { it.reachable() }) }

    private fun body(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun playing(c: KodiControl, startAtMs: Long = 0) {
        c.load("http://pi:8771/c/ab/hls.m3u8", "application/vnd.apple.mpegurl", "Film", startAtMs)
    }

    @Test
    fun `connecting reads the version, the add-ons and the volume, and plays and sets nothing`() {
        val c = ready()

        assertEquals(listOf("JSONRPC.Version", "Addons.GetAddons", "Application.GetProperties"), kodi.methods())
        assertEquals("Kodi 21.3", c.model)
        assertEquals(40, c.volumePercent())
        assertEquals(CastFormat.HLS, c.format)
        assertTrue(c.showsPictures)
        assertTrue(c.keepsAddressesPrivate)
        assertTrue(c.screen!!.codecs.containsAll(listOf(VideoCodecFamily.AVC, VideoCodecFamily.HEVC)))
        assertEquals(1080, c.screen!!.maxHeightOf(VideoCodecFamily.AVC))
    }

    @Test
    fun `with inputstream adaptive present DASH is possible but HLS stays unless it is asked for`() {
        kodi.adaptive = true

        assertEquals(CastFormat.HLS, ready().format)
        assertEquals(CastFormat.DASH, ready(control(dash = true)).format)
        kodi.adaptive = false
        assertEquals(CastFormat.HLS, ready(control(dash = true)).format)
    }

    @Test
    fun `a load opens the address with the player, never resuming, and sets no volume`() {
        val c = ready()

        playing(c, 5_000)

        assertEquals(
            body("""{"item":{"file":"http://pi:8771/c/ab/hls.m3u8"},"options":{"resume":false}}"""),
            kodi.last("Player.Open"),
        )
        assertEquals(0, kodi.count("Application.SetVolume"))
        assertEquals(40, kodi.volume)
    }

    @Test
    fun `polling reads Kodi's own time, length and speed, and runs on from there between polls`() {
        val c = ready()
        playing(c)
        kodi.timeMs = 3_725_040
        kodi.totalMs = 7_200_000

        c.poll()
        val reading = c.reading()!!

        assertEquals(3_725_040L, reading.positionMs)
        assertEquals(7_200_000L, reading.durationMs)
        assertTrue(reading.playing)
        now += 2_000
        assertEquals(3_727_040L, reading.positionAt(now))
        assertEquals(
            body("""{"playerid":1,"properties":["time","totaltime","speed"]}"""),
            kodi.last("Player.GetProperties")
        )
    }

    @Test
    fun `a place to start at is sought once Kodi is playing, as a time, and only once`() {
        val c = ready()
        playing(c, 125_000)
        assertEquals(0, kodi.count("Player.Seek"))

        c.poll()
        c.poll()

        assertEquals(1, kodi.count("Player.Seek"))
        assertEquals(
            body("""{"playerid":1,"value":{"time":{"hours":0,"minutes":2,"seconds":5,"milliseconds":0}}}"""),
            kodi.last("Player.Seek"),
        )
    }

    @Test
    fun `pause and resume are PlayPause with an explicit state, seek is a time, stop is Stop`() {
        val c = ready()
        playing(c)
        c.poll()

        c.pause(true)
        assertEquals(body("""{"playerid":1,"play":false}"""), kodi.last("Player.PlayPause"))
        assertEquals(0, kodi.speed)
        c.pause(false)
        assertEquals(body("""{"playerid":1,"play":true}"""), kodi.last("Player.PlayPause"))
        c.seek(3_725_040)
        assertEquals(
            body("""{"playerid":1,"value":{"time":{"hours":1,"minutes":2,"seconds":5,"milliseconds":40}}}"""),
            kodi.last("Player.Seek"),
        )
        c.stop()

        assertEquals(body("""{"playerid":1}"""), kodi.last("Player.Stop"))
        assertNull(c.reading())
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `the volume slider writes Kodi's application volume and is read back, and nothing else does`() {
        val c = ready()
        playing(c)
        c.poll()

        c.volume(12)

        assertEquals(body("""{"volume":12}"""), kodi.last("Application.SetVolume"))
        assertEquals(12, kodi.volume)
        assertEquals(12, c.volumePercent())
        assertEquals(1, kodi.count("Application.SetVolume"))
    }

    @Test
    fun `an item that played and then left no player is over, once, and not failed`() {
        val c = ready()
        playing(c)
        c.poll()

        kodi.playing = false
        c.poll()
        c.poll()

        assertEquals(listOf(false), ended)
    }

    @Test
    fun `a player that never appears is a failed item once it has had its time, and not before`() {
        val c = ready()
        playing(c)
        kodi.playing = false

        c.poll()
        assertTrue(ended.isEmpty())
        now += 21_000
        c.poll()

        assertEquals(listOf(true), ended)
    }

    @Test
    fun `an open Kodi refuses is a failed item and nothing is left loaded`() {
        val c = ready()
        kodi.refuse = setOf("Player.Open")

        playing(c)
        c.poll()

        assertEquals(listOf(true), ended)
        assertEquals(0, kodi.count("Player.GetActivePlayers"))
    }

    @Test
    fun `three missed polls lose Kodi once, and a fresh item is not ended by the one before`() {
        val c = ready()
        playing(c)
        kodi.down = true

        repeat(5) { c.poll() }

        assertEquals(listOf("Kodi stopped answering"), lost)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `a long queue costs Kodi one open per item and nothing read ahead`() {
        val c = ready()
        val before = kodi.methods().size

        repeat(300) { playing(c) }

        assertEquals(before + 300, kodi.methods().size)
        assertEquals(300, kodi.count("Player.Open"))
    }

    @Test
    fun `the sign-in is a Basic header read from the file at each call, never in the address or the logs`() {
        Diag.sink = Diag.Sink { _, _, message, error -> logged += message + (error?.message ?: "") }
        val dir = java.nio.file.Files.createTempDirectory("kodi-rotate").toFile()
        val file = kodi.credentialsFile(dir = dir)
        val c = ready(control(file = file))
        playing(c)
        c.pause(true)

        assertTrue(kodi.authorizations.isNotEmpty())
        assertTrue(kodi.authorizations.all { it == kodi.expectedAuthorization })
        assertTrue(kodi.requestLines.all { it == "POST /jsonrpc ${kodi.address}" })
        assertTrue(logged.joinToString("\n"), logged.none { it.contains("SECRET") || it.contains(kodi.login) })
        file.writeText("user=other\npassword=rotated\n")
        assertFalse(runBlocking { control(file = file).reachable() })
        assertTrue(kodi.authorizations.last() != kodi.expectedAuthorization)
    }

    @Test
    fun `a refused sign-in or no credentials file is not reachable, says which, and leaks neither`() {
        Diag.sink = Diag.Sink { _, _, message, error -> logged += message + (error?.message ?: "") }
        val wrong = kodi.credentialsFile(password = "not-the-password")

        assertFalse(runBlocking { control(file = wrong).reachable() })
        assertFalse(runBlocking { control(file = null).reachable() })

        val text = logged.joinToString("\n")
        assertTrue(text, text.contains("refused the sign-in"))
        assertTrue(text, text.contains("TOTUM_KODI_CREDENTIALS"))
        assertFalse(text, text.contains("not-the-password"))
        assertEquals(1, kodi.authorizations.size)
    }

    @Test
    fun `a Kodi that is not running is not reachable`() = runBlocking {
        val gone = FakeKodi().also { it.close() }

        assertFalse(control(fake = gone).reachable())
    }

    @Test
    fun `the address is a host and port or a full URL, and the host alone is where a wake would look`() {
        assertEquals("http://192.168.0.211:8080/jsonrpc", kodiEndpoint("192.168.0.211:8080"))
        assertEquals("http://k:8080/jsonrpc", kodiEndpoint("http://k:8080/"))
        assertEquals("https://k/jsonrpc", kodiEndpoint("https://k/jsonrpc"))
        assertEquals("192.168.0.211:8080", kodiHostPort("http://192.168.0.211:8080/jsonrpc"))
        assertEquals("192.168.0.211", hostOf("192.168.0.211:8080"))
        assertEquals(8080, portOf("192.168.0.211:8080"))
        assertEquals("192.168.0.211", hostOf("http://192.168.0.211:1285/"))
        assertNull(portOf("192.168.0.19"))
    }

    @Test
    fun `credentials are user and password lines and the login never prints them`() {
        val file = kodi.credentialsFile()

        val login = KodiCredentials.fromFile(file).read()!!

        assertEquals("kodi-user", login.user)
        assertFalse(login.toString().contains("SECRET"))
        assertNull(KodiCredentials.fromFile(null).read())
        file.writeText("host=1.2.3.4:8080\n")
        assertNull(KodiCredentials.fromFile(file).read())
    }

    @Test
    fun `a time of zero is zero in every part`() {
        val c = ready()
        playing(c)
        c.poll()

        c.seek(0)

        assertEquals(
            buildJsonObject {
                put("playerid", 1)
                put(
                    "value",
                    buildJsonObject {
                        put(
                            "time",
                            buildJsonObject {
                                put("hours", 0)
                                put("minutes", 0)
                                put("seconds", 0)
                                put("milliseconds", 0)
                            }
                        )
                    }
                )
            },
            kodi.last("Player.Seek"),
        )
    }
}
