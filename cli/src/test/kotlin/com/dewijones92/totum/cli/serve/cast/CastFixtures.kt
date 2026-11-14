package com.dewijones92.totum.cli.serve.cast

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal class CastFixtures {
    val dir: File = Files.createTempDirectory("cast-fixtures").toFile()

    private fun ffmpeg(vararg args: String) {
        val made = ProcessBuilder(listOf("ffmpeg", "-v", "error", "-y") + args).redirectErrorStream(true).start()
        val said = made.inputStream.bufferedReader().readText()
        assertTrue("ffmpeg is needed for this test (CI installs it): $said", made.waitFor(60, TimeUnit.SECONDS))
        assertEquals(said, 0, made.exitValue())
    }

    fun tone(name: String, seconds: Int): File = File(dir, name).also {
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds", "-ar", "22050", "-ac", "1", it.path)
    }

    fun toneSilenceTone(name: String): File = File(dir, name).also {
        ffmpeg(
            "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
            "-f", "lavfi", "-i", "anullsrc=r=22050:cl=mono",
            "-f", "lavfi", "-i", "sine=frequency=330:duration=3",
            "-filter_complex", "[1:a]atrim=duration=4[s];[0:a][s][2:a]concat=n=3:v=0:a=1",
            "-ar", "22050", "-ac", "1", it.path,
        )
    }

    fun webm(name: String, seconds: Int): File = File(dir, name).also {
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=duration=$seconds:size=320x180:rate=10",
            "-c:v", "libvpx-vp9", "-b:v", "80k", "-an", "-f", "webm", "-dash", "1", "-cues_to_front", "1", it.path,
        )
    }
}

internal class FakeOrigin(private val files: Map<String, File>) : AutoCloseable {
    val requests = mutableListOf<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> answer(exchange) }
        start()
    }

    val port: Int get() = server.address.port

    fun url(path: String, extra: String = ""): String = "http://127.0.0.1:$port/$path?host=googlevideo.com$extra"

    private fun answer(exchange: HttpExchange) {
        val path = exchange.requestURI.path.trim('/')
        val range = exchange.requestHeaders.getFirst("Range")
        requests += "$path ${range ?: "-"} ${exchange.requestURI.query.orEmpty()}"
        val live = Regex("""live-(\d+)""").matchEntire(path)
        val body = when {
            live != null -> "segment-${exchange.requestURI.query.orEmpty().substringAfter("sq=")}".toByteArray()
            else -> files[path]?.readBytes()
        }
        if (body == null) {
            exchange.sendResponseHeaders(NOT_FOUND, -1)
            exchange.close()
            return
        }
        val spec = range?.removePrefix("bytes=")
        if (spec == null) {
            exchange.sendResponseHeaders(OK, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            return
        }
        val first = spec.substringBefore('-').toLong()
        val last = spec.substringAfter('-').toLongOrNull()?.coerceAtMost(body.size - 1L) ?: (body.size - 1L)
        val slice = body.copyOfRange(first.toInt(), last.toInt() + 1)
        exchange.responseHeaders.add("Content-Range", "bytes $first-$last/${body.size}")
        exchange.sendResponseHeaders(PARTIAL, slice.size.toLong())
        exchange.responseBody.use { it.write(slice) }
    }

    override fun close() = server.stop(0)

    private companion object {
        const val OK = 200
        const val PARTIAL = 206
        const val NOT_FOUND = 404
    }
}

internal class Fetched(val status: Int, val type: String?, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

internal fun fetch(url: String, range: String? = null): Fetched {
    val connection = URL(url).openConnection() as HttpURLConnection
    range?.let { connection.setRequestProperty("Range", it) }
    val status = connection.responseCode
    val stream = if (status >= 400) connection.errorStream else connection.inputStream
    return Fetched(status, connection.contentType, stream?.use { it.readBytes() } ?: ByteArray(0))
}

internal class Loaded(val url: String, val type: String, val title: String, val startAtMs: Long)

internal class FakeCastControl : CastControl {
    val loads = mutableListOf<Loaded>()
    val calls = mutableListOf<String>()
    var reading: CastReading? = null
    var reachable = true
    var deviceVolume: Int? = null
    override var model: String? = "webOS"
    override var keepsAddressesPrivate: Boolean = false
    override var format: com.dewijones92.totum.playback.cast.CastFormat =
        com.dewijones92.totum.playback.cast.CastFormat.DASH

    override fun load(url: String, contentType: String, title: String, startAtMs: Long) {
        loads += Loaded(url, contentType, title, startAtMs)
    }

    override fun pause(paused: Boolean) {
        calls += "pause=$paused"
    }

    override fun seek(positionMs: Long) {
        calls += "seek=$positionMs"
    }

    override fun volume(percent: Int) {
        calls += "volume=$percent"
    }

    override fun volumePercent(): Int? = deviceVolume

    override fun stop() {
        calls += "stop"
    }

    override fun release() {
        calls += "release"
    }

    var onReading: () -> Unit = {}

    override fun reading(): CastReading? {
        onReading()
        return reading
    }

    override var onEnded: (failed: Boolean) -> Unit = {}

    override var onLost: (reason: String) -> Unit = {}

    val reachableAnswers = ArrayDeque<Boolean>()
    var reachableAsked = 0

    override suspend fun reachable(): Boolean {
        reachableAsked++
        return reachableAnswers.removeFirstOrNull() ?: reachable
    }
}
