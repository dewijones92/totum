package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files

class SharedPicturesTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val host = FakeCastHost("http://phone:$port")
    private val dir = Files.createTempDirectory("shared-pictures").toFile()
    private val server = CastStreamServer(
        engines = { FakeAudioEngine() },
        skipSilence = { false },
        host = host,
        tvs = FakeCastTv(),
        tools = null,
        port = port,
    )

    @After
    fun tidy() {
        server.stopAll()
    }

    private fun picture(name: String, size: Int = 64): File =
        File(dir, name).apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }

    private class Fetched(val status: Int, val type: String?, val body: ByteArray, val contentRange: String?)

    private fun fetch(url: String, range: String? = null): Fetched {
        val local = url.replace("http://phone:$port", "http://127.0.0.1:$port")
        val connection = URL(local).openConnection() as HttpURLConnection
        range?.let { connection.setRequestProperty("Range", it) }
        val status = connection.responseCode
        val stream = if (status >= HTTP_ERROR) connection.errorStream else connection.inputStream
        return Fetched(
            status,
            connection.contentType,
            stream?.use { it.readBytes() } ?: ByteArray(0),
            connection.getHeaderField("Content-Range"),
        )
    }

    @Test
    fun `a picture on the phone is served at an address a screen can load, as the picture it is`() {
        val file = picture("holiday.JPG")

        val url = server.sharePicture(file)!!

        assertTrue(url, Regex("""http://phone:$port/p/[0-9a-f]{16}/picture\.jpg""").matches(url))
        val got = fetch(url)
        assertEquals(200, got.status)
        assertEquals("image/jpeg", got.type)
        assertEquals(file.readBytes().toList(), got.body.toList())
    }

    @Test
    fun `a range is answered with exactly those bytes`() {
        val file = picture("wide.png", size = 100)

        val got = fetch(server.sharePicture(file)!!, range = "bytes=10-19")

        assertEquals(206, got.status)
        assertEquals("bytes 10-19/100", got.contentRange)
        assertEquals(file.readBytes().copyOfRange(10, 20).toList(), got.body.toList())
    }

    @Test
    fun `each share gets its own unguessable address`() {
        val first = server.sharePicture(picture("a.jpg"))!!
        val second = server.sharePicture(picture("b.jpg"))!!

        assertTrue(first != second)
        assertEquals(200, fetch(first).status)
        assertEquals(200, fetch(second).status)
    }

    @Test
    fun `an address nobody was given answers not found and never a file`() {
        server.sharePicture(picture("a.jpg"))

        assertEquals(404, fetch("http://phone:$port/p/0000000000000000/picture.jpg").status)
        assertEquals(404, fetch("http://phone:$port/p/../../etc/passwd").status)
    }

    @Test
    fun `only the pictures a browser can show are shared`() {
        assertNull(server.sharePicture(picture("clip.mp4")))
        assertNull(server.sharePicture(picture("photo.heic")))
        assertNull(server.sharePicture(File(dir, "missing.jpg")))
    }

    @Test
    fun `no address a screen can reach means nothing is shared`() {
        host.base = null

        assertNull(server.sharePicture(picture("a.jpg")))
    }

    @Test
    fun `only the latest sixteen stay shared`() {
        val urls = (1..17).map { server.sharePicture(picture("p$it.jpg"))!! }

        assertEquals(404, fetch(urls.first()).status)
        assertEquals(200, fetch(urls.last()).status)
        assertEquals(200, fetch(urls[1]).status)
    }

    private companion object {
        const val HTTP_ERROR = 400
    }
}
