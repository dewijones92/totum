package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.Command
import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetsJson
import com.dewijones92.totum.playback.cast.CastFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CastDevicesTest {

    private val tmp = Files.createTempDirectory("cast-devices").toFile()

    private fun streams() = PiStreams(ProcessedSound("ffmpeg-not-run") { null }, "http://pi:8771", 8771)

    private fun devices(
        cast: String? = null,
        airplay: String? = null,
        dlna: String? = null,
        first: String? = null,
    ) = knownDevicesFrom(knownCastsFrom(cast), knownCastsFrom(airplay), knownCastsFrom(dlna), first)

    @Test
    fun `one name in two lists is one device, driven over Chromecast first unless it says otherwise`() {
        val list = devices(cast = "LG TV=192.168.0.219@20:28:bc:6b:f7:f8", airplay = "LG TV=192.168.0.219")

        assertEquals(listOf("lg-tv"), list.map { it.id })
        assertEquals(listOf(CastProtocol.CHROMECAST, CastProtocol.AIRPLAY), list.single().order)
        assertEquals("20:28:bc:6b:f7:f8", list.single().mac)
        assertEquals(
            listOf(CastProtocol.AIRPLAY, CastProtocol.CHROMECAST),
            devices(
                cast = "LG TV=192.168.0.219",
                airplay = "LG TV=192.168.0.219",
                first = "LG TV=airplay"
            ).single().order,
        )
    }

    @Test
    fun `a first protocol the device does not have is ignored, and an unknown word is no preference`() {
        assertEquals(
            listOf(CastProtocol.CHROMECAST),
            devices(cast = "TV=1.2.3.4", first = "TV=dlna").single().order,
        )
        assertEquals(
            listOf(CastProtocol.CHROMECAST, CastProtocol.DLNA),
            devices(cast = "TV=1.2.3.4", dlna = "TV=http://1.2.3.4:1/d.xml", first = "TV=bluetooth").single().order,
        )
    }

    @Test
    fun `a DLNA address keeps its URL, its host is where a wake would look, and its port is a wake probe`() {
        val device = devices(dlna = "Kodi=http://192.168.0.211:1285/@aa:bb:cc:dd:ee:ff").single()

        assertEquals("http://192.168.0.211:1285/", device.addresses.getValue(CastProtocol.DLNA).host)
        assertEquals("192.168.0.211", device.host)
        assertEquals("aa:bb:cc:dd:ee:ff", device.mac)
        assertEquals("dlna (wake aa:bb:cc:dd:ee:ff)", device.describe())
    }

    @Test
    fun `devices found over AirPlay or DLNA are TVs on the wire, a kind every older phone knows`() = runBlocking {
        val found = CastDeviceSource(
            devices(
                cast = "Chromecast Ultra=1.1.1.1",
                airplay = "Apple TV=2.2.2.2",
                dlna = "Kodi=http://3.3.3.3:1285/",
            ),
            streams(),
        ).find(false)

        assertEquals(listOf(TargetKind.CHROMECAST, TargetKind.TV, TargetKind.TV), found.map { it.target.kind })
        assertEquals(listOf("chromecast-ultra", "apple-tv", "kodi"), found.map { it.target.id })
        val oldKinds = setOf("SPEAKER", "TV", "CHROMECAST", "PHONE")
        found.forEach {
            val wire = TargetsJson.encodeToString(Target.serializer(), it.target)
            assertTrue(wire, oldKinds.any { kind -> wire.contains("\"kind\":\"$kind\"") })
        }
    }

    @Test
    fun `each device is built with the control its protocol needs, and the player asks that control for its format`() =
        runBlocking {
            val found = CastDeviceSource(
                devices(airplay = "Apple TV=2.2.2.2", dlna = "Kodi=http://3.3.3.3:1285/"),
                streams(),
                places = CastPlaces(tmp, File(tmp, "airplay"), airplayHelper = "/x/airplay_helper.py"),
            ).find(false)

            val kits = found.map { it.kit() }

            assertEquals(2, kits.size)
            assertTrue(File(tmp, "airplay").isDirectory)
        }

    @Test
    fun `a DLNA or AirPlay device is never handed an address that carries a key, a Chromecast keeps its rule`() =
        runBlocking {
            val device = devices(
                cast = "TV=1.1.1.1",
                airplay = "TV=1.1.1.1",
                dlna = "TV=http://1.1.1.1:1/d.xml"
            ).single()
            val source = CastDeviceSource(listOf(device), streams())

            assertFalse(source.controlFor(device, CastProtocol.CHROMECAST).keepsAddressesPrivate)
            assertTrue(source.controlFor(device, CastProtocol.AIRPLAY).keepsAddressesPrivate)
            assertTrue(source.controlFor(device, CastProtocol.DLNA).keepsAddressesPrivate)
            val both = FirstReachableControl(
                "TV",
                listOf(
                    CastProtocol.CHROMECAST to Stub(true, CastFormat.DASH),
                    CastProtocol.DLNA to Stub(true, CastFormat.HLS)
                )
            )
            assertFalse(both.keepsAddressesPrivate)
        }

    private class Stub(
        var answers: Boolean,
        override val format: CastFormat,
    ) : CastControl {
        val calls = mutableListOf<String>()
        override var onEnded: (Boolean) -> Unit = {}
        override var onLost: (String) -> Unit = {}
        override suspend fun reachable() = answers.also { calls += "reachable=$it" }
        override fun load(url: String, contentType: String, title: String, startAtMs: Long) {
            calls += "load $url"
        }
        override fun pause(paused: Boolean) {
            calls += "pause=$paused"
        }
        override fun seek(positionMs: Long) {
            calls += "seek"
        }
        override fun volume(percent: Int) {
            calls += "volume=$percent"
        }
        override fun volumePercent(): Int? = null
        override fun stop() {
            calls += "stop"
        }
        override fun release() {
            calls += "release"
        }
        override fun reading(): CastReading? = null
    }

    @Test
    fun `a device with two protocols uses the first that answers, and tells the player its format`() = runBlocking {
        val cast = Stub(false, CastFormat.DASH)
        val airplay = Stub(true, CastFormat.HLS)
        val both = FirstReachableControl(
            "LG TV",
            listOf(CastProtocol.CHROMECAST to cast, CastProtocol.AIRPLAY to airplay)
        )

        assertEquals(CastFormat.DASH, both.format)
        assertTrue(both.reachable())
        both.load("u", "t", "T", 0)
        both.pause(true)

        assertEquals(CastFormat.HLS, both.format)
        assertEquals(listOf("reachable=false"), cast.calls.filterNot { it == "stop" })
        assertEquals(listOf("reachable=true", "load u", "pause=true"), airplay.calls)
    }

    @Test
    fun `the protocol that last answered is tried first, and a switch stops what the old one held`() = runBlocking {
        val cast = Stub(true, CastFormat.DASH)
        val airplay = Stub(true, CastFormat.HLS)
        val both = FirstReachableControl(
            "LG TV",
            listOf(CastProtocol.CHROMECAST to cast, CastProtocol.AIRPLAY to airplay)
        )
        both.reachable()
        cast.answers = false

        assertTrue(both.reachable())

        assertEquals(listOf("reachable=true", "reachable=false", "stop"), cast.calls)
        assertEquals(CastFormat.HLS, both.format)
        cast.answers = true
        both.reachable()
        assertEquals("reachable=true", airplay.calls.last())
        assertEquals(CastFormat.HLS, both.format)
    }

    @Test
    fun `with no protocol answering the device is not reachable, and an end from one not in use is ignored`() =
        runBlocking {
            val cast = Stub(false, CastFormat.DASH)
            val airplay = Stub(false, CastFormat.HLS)
            val both = FirstReachableControl(
                "TV",
                listOf(CastProtocol.CHROMECAST to cast, CastProtocol.AIRPLAY to airplay)
            )
            val ended = mutableListOf<Boolean>()
            both.onEnded = { ended += it }

            assertFalse(both.reachable())
            airplay.onEnded(false)
            cast.onEnded(true)

            assertEquals(listOf(true), ended)
        }

    @Test
    fun `pairing needs the device configured, and runs the helper with the credentials file under the data dir`() {
        val said = mutableListOf<String>()
        val ran = mutableListOf<List<String>>()
        val env = mapOf(
            "TOTUM_AIRPLAY_TARGETS" to "LG TV=192.168.0.219",
            "TOTUM_DATA" to tmp.path,
            "TOTUM_AIRPLAY_HELPER" to "/opt/totum/airplay_helper.py",
        )

        val refused = airPlayPair(Command.AirPlayPair("Bedroom", null), said::add, env::get) { 0 }
        val code = airPlayPair(Command.AirPlayPair("lg tv", "1234"), said::add, env::get) {
            ran += it
            0
        }

        assertEquals(1, refused)
        assertTrue(said.first(), said.first().contains("not in TOTUM_AIRPLAY_TARGETS"))
        assertEquals(0, code)
        val command = ran.single()
        assertEquals(
            listOf("python3", "/opt/totum/airplay_helper.py", "pair", "192.168.0.219", "--credentials"),
            command.take(5),
        )
        assertEquals(File(tmp, "airplay/airplay-lg-tv.json").path, command[5])
        assertTrue(command.contains("--pin=1234"))
        assertFalse(said.joinToString().contains("1234"))
    }

    @Test
    fun `a kind this build does not know cannot be read, so a Pi never reports a kind newer than the old ones`() {
        val wire = """{"id":"x","label":"X","kind":"AIRPLAY"}"""

        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            TargetsJson.decodeFromString(Target.serializer(), wire)
        }
    }
}
