package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.data.targets.SoundSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class SoundStreamsTest {

    private val dir = Files.createTempDirectory("sound-streams").toFile()
    private val port = ServerSocket(0).use { it.localPort }
    private var now = 1_000_000L
    private val http = OkHttpClient()

    private val ffmpeg = File(dir, "ffmpeg.sh").apply {
        writeText(
            "#!/bin/sh\ncase \"\$*\" in *adts*) cat;; *) head -c 8192 /dev/zero;; esac\n",
        )
        setExecutable(true)
    }.path

    private fun streams(base: String? = "http://pi.example:$port/") =
        SoundStreams(ProcessedSound(ffmpeg) { null }, base, port) { now }

    private fun fetch(url: String, range: String? = null): Triple<Int, String?, ByteArray> {
        val local = "http://127.0.0.1:$port${url.substringAfter("pi.example:$port")}"
        val request = Request.Builder().url(local).apply { range?.let { header("Range", it) } }.build()
        return http.newCall(request).execute().use { Triple(it.code, it.header("Content-Type"), it.body.bytes()) }
    }

    @Test
    fun `with no base address nothing is offered and the server never starts`() {
        val s = streams(base = null)

        assertTrue(!s.available)
        assertNull(s.offer("TV", "https://x/a.mp3", SoundSettings(), 0))
        assertNull(s.offerFile("TV", File(dir, "none.mp3")))
    }

    @Test
    fun `an offer is an unguessable address under the base and serves processed AAC sound`() {
        val s = streams()

        val url = s.offer("TV", "https://x/a.mp3", SoundSettings(), 0) ?: error("no offer")

        assertTrue(url, Regex("""http://pi\.example:$port/s/[0-9a-f]{32}\.aac""").matches(url))
        val (code, type, body) = fetch(url)
        assertEquals(200, code)
        assertEquals("audio/aac", type)
        assertArrayEquals(ByteArray(8192), body)
    }

    @Test
    fun `two offers never share an address`() {
        val s = streams()

        val one = s.offer("TV", "https://x/a.mp3", SoundSettings(), 0)
        val two = s.offer("TV", "https://x/a.mp3", SoundSettings(), 0)

        assertNotEquals(one, two)
        assertEquals(2, s.offered())
    }

    @Test
    fun `an address nobody offered is a 404`() {
        val s = streams()
        s.offer("TV", "https://x/a.mp3", SoundSettings(), 0)

        val (code, _, _) = fetch("http://pi.example:$port/s/${"0".repeat(32)}.aac")

        assertEquals(404, code)
    }

    @Test
    fun `a file is offered with its extension and served by range`() {
        val file = File(dir, "film.mp4").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        val s = streams()

        val url = s.offerFile("TV", file) ?: error("no offer")

        assertTrue(url, Regex("""http://pi\.example:$port/f/[0-9a-f]{32}\.mp4""").matches(url))
        val (code, type, body) = fetch(url, "bytes=10-19")
        assertEquals(206, code)
        assertEquals("video/mp4", type)
        assertArrayEquals(ByteArray(10) { (10 + it).toByte() }, body)
    }

    @Test
    fun `a file with no extension is offered as bin`() {
        val url = streams().offerFile("TV", File(dir, "noext").apply { writeBytes(ByteArray(4)) })

        assertTrue(url, url!!.endsWith(".bin"))
    }

    @Test
    fun `withdrawing an offer or a file makes its address a 404`() {
        val s = streams()
        val file = File(dir, "film.mp4").apply { writeBytes(ByteArray(10)) }
        val sound = s.offer("TV", "https://x/a.mp3", SoundSettings(), 0)!!
        val picture = s.offerFile("TV", file)!!
        assertEquals(200, fetch(picture).first)

        s.withdraw(sound)
        s.withdraw(picture)

        assertEquals(404, fetch(sound).first)
        assertEquals(404, fetch(picture).first)
        assertEquals(0, s.offered())
    }

    @Test
    fun `an offer nobody fetched within ten minutes is dropped when the next is made`() {
        val s = streams()
        s.offer("TV", "https://x/a.mp3", SoundSettings(), 0)

        now += 9 * 60_000
        s.offer("TV", "https://x/b.mp3", SoundSettings(), 0)
        assertEquals(2, s.offered())

        now += 2 * 60_000
        s.offer("TV", "https://x/c.mp3", SoundSettings(), 0)
        assertEquals(2, s.offered())
    }

    @Test
    fun `the silence skipped so far is asked of the stream by its address, and is nothing for another`() {
        val s = streams()
        val url = s.offer("TV", "https://x/a.mp3", SoundSettings(), 0)!!

        assertEquals(0L, s.skippedMs(url))
        assertEquals(0L, s.skippedMs("http://pi.example:$port/s/${"1".repeat(32)}.aac"))
        assertEquals(0L, s.skippedMs("https://not-an-offer"))
    }
}
