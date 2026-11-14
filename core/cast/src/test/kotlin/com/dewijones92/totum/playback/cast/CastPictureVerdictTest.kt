package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class CastPictureVerdictTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val host = FakeCastHost("http://pi:$port")
    private val tv = FakeCastTv()
    private val dir = Files.createTempDirectory("cast-picture").toFile()
    private val origins = mutableListOf<RangeOrigin>()
    private val lines = mutableListOf<String>()
    private val previousSink = Diag.sink
    private val server = CastStreamServer(
        engines = { FakeAudioEngine() },
        skipSilence = { false },
        host = host,
        tvs = tv,
        tools = null,
        port = port,
    )

    init {
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "cast") lines += message }
    }

    @After
    fun tidy() {
        Diag.sink = previousSink
        server.stopAll()
        origins.forEach { it.close() }
    }

    private fun film(saveAs: String = "film.mkv"): File = File(dir, saveAs).also {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/cast/avc.mkv")).use { stream -> stream.readBytes() }
        it.writeBytes(bytes)
    }

    private fun manifestOf(item: CastItem): String {
        val url = server.urlFor(item)!!
        return String(fetchFrom(url.replace("http://pi:$port", "http://127.0.0.1:$port")).second)
    }

    private fun handedOverLine(mediaId: String) = lines.single { it.startsWith("$mediaId -> ") }

    @Test
    fun `a Pi copy whose address names no file type is probed and casts with its picture`() {
        val origin = RangeOrigin(mapOf("fetch/files" to film())).also { origins += it }

        val address = origin.url("fetch/files") + "?id=torrentabc123"

        val manifest = manifestOf(CastItem("pi-copy", address, durationMs = 4_000))

        assertTrue(manifest, manifest.contains("contentType=\"video\""))
        assertTrue(handedOverLine("pi-copy"), "the address names no file type (files)" in handedOverLine("pi-copy"))
        assertTrue(handedOverLine("pi-copy"), "video=passed through" in handedOverLine("pi-copy"))
    }

    @Test
    fun `an address whose name says sound is never given a picture, even when its bytes hold one`() {
        val origin = RangeOrigin(mapOf("song.mp3" to film())).also { origins += it }

        val manifest = manifestOf(CastItem("sound", origin.url("song.mp3"), durationMs = 4_000))

        assertFalse(manifest, manifest.contains("contentType=\"video\""))
        assertTrue(handedOverLine("sound"), "the file name says sound (song.mp3)" in handedOverLine("sound"))
        assertTrue(handedOverLine("sound"), "video=none" in handedOverLine("sound"))
    }

    @Test
    fun `an address whose name says video is cast with its picture and says so`() {
        val origin = RangeOrigin(mapOf("film.mkv" to film())).also { origins += it }

        val manifest = manifestOf(CastItem("named", origin.url("film.mkv"), durationMs = 4_000))

        assertTrue(manifest, manifest.contains("contentType=\"video\""))
        assertTrue(handedOverLine("named"), "the file name says video (film.mkv)" in handedOverLine("named"))
    }

    @Test
    fun `a file on the phone says its bytes will decide`() {
        val manifest = manifestOf(CastItem("local", film().path, durationMs = 4_000))

        assertTrue(manifest, manifest.contains("contentType=\"video\""))
        val said = "a file on the phone; its bytes will say (film.mkv)"
        assertTrue(handedOverLine("local"), said in handedOverLine("local"))
    }

    @Test
    fun `listening gives sound only and names the choice as the reason`() {
        val origin = RangeOrigin(mapOf("film.mkv" to film())).also { origins += it }

        val manifest = manifestOf(CastItem("listen", origin.url("film.mkv"), durationMs = 4_000, listening = true))

        assertFalse(manifest, manifest.contains("contentType=\"video\""))
        assertTrue(handedOverLine("listen"), "listening (the picture is off by choice)" in handedOverLine("listen"))
    }

    @Test
    fun `an address with no file type whose bytes are not a video is sound only, and the probe says why`() {
        val notes = File(dir, "notes.bin").apply { writeText("not a film at all") }
        val origin = RangeOrigin(mapOf("fetch/files" to notes)).also { origins += it }

        val manifest = manifestOf(CastItem("no-picture", origin.url("fetch/files") + "?id=x", durationMs = 4_000))

        assertFalse(manifest, manifest.contains("contentType=\"video\""))
        assertTrue(handedOverLine("no-picture"), "the address names no file type" in handedOverLine("no-picture"))
        assertTrue(lines.toString(), lines.any { it.contains("no-picture") && it.contains("sound only", true) })
    }
}
