package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.playback.CastProblem
import com.dewijones92.totum.playback.CastStage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

internal class FakeAudioEngine(
    private val durationUs: Long = 10_000_000,
    private val segments: Int = 3,
) : AudioEngine {
    var closed = false
    val plans = mutableListOf<TranscodePlan>()
    override val summary: String get() = "fake"
    override fun probe() = AudioProbe(48_000, 2, durationUs)
    override fun silentFrame(format: AacFormat) = byteArrayOf(9, 9, 9)
    override fun transcode(plan: TranscodePlan, sink: TranscodeSink) {
        plans += plan
        for (i in plan.first until plan.first + segments) {
            if (sink.stale()) return
            val tvEnd = plan.samplesToUs((i + 1) * plan.segmentSamples)
            val bytes = Fmp4Aac.segment(i + 1, i * plan.segmentSamples, listOf(byteArrayOf(1, 2, 3)))
            sink.publish(i, bytes, tvEnd, tvEnd)
        }
        sink.ended(plan.samplesToUs((plan.first + segments) * plan.segmentSamples))
    }

    override fun close() {
        closed = true
    }
}

internal class FakeCastHost(var base: String?) : CastHostPort {
    override val vpnInTheWay = false
    val holds = mutableListOf<Boolean>()
    override fun baseUrl(port: Int): String? = base
    override fun hold(active: Boolean) {
        holds += active
    }
}

internal class FakeCastTv(var screen: CastScreen? = null) : CastTvPort {
    val problems = mutableListOf<CastProblem?>()
    val stages = mutableListOf<Pair<String, CastStage?>>()
    var refused: Map<String, String> = emptyMap()
    var sent: Map<String, QualityLabel> = emptyMap()
    override fun current(): CastTv? = screen?.let { CastTv("TV", it) }
    override fun videos(
        refused: Map<String, String>,
        sent: Map<String, QualityLabel>,
        fetchingHeights: Map<String, Int>,
        droppedFrom: Map<String, Int>,
    ) {
        this.refused = refused
        this.sent = sent
    }

    override fun problem(now: CastProblem?) {
        problems += now
    }

    override fun stage(mediaId: String, now: CastStage?, percent: Int?) {
        stages += mediaId to now
    }
}

internal fun fetchFrom(url: String, range: String? = null): Pair<Int, ByteArray> {
    val connection = URL(url).openConnection() as HttpURLConnection
    range?.let { connection.setRequestProperty("Range", it) }
    val status = connection.responseCode
    val stream = if (status >= HTTP_ERROR) connection.errorStream else connection.inputStream
    return status to (stream?.use { it.readBytes() } ?: ByteArray(0))
}

private const val HTTP_ERROR = 400

internal class RangeOrigin(private val files: Map<String, File>) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val file = files[exchange.requestURI.path.trim('/')]
            val body = file?.readBytes()
            val spec = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
            when {
                body == null -> exchange.sendResponseHeaders(NOT_FOUND, -1)
                spec == null -> {
                    exchange.sendResponseHeaders(OK, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                else -> {
                    val first = spec.substringBefore('-').toInt()
                    val last = spec.substringAfter('-').toIntOrNull()?.coerceAtMost(body.size - 1) ?: (body.size - 1)
                    exchange.responseHeaders.add("Content-Range", "bytes $first-$last/${body.size}")
                    exchange.sendResponseHeaders(PARTIAL, (last - first + 1).toLong())
                    exchange.responseBody.use { it.write(body, first, last - first + 1) }
                }
            }
            exchange.close()
        }
        start()
    }

    val port: Int get() = server.address.port

    fun url(name: String): String = "http://127.0.0.1:$port/$name"

    override fun close() = server.stop(0)

    private companion object {
        const val OK = 200
        const val PARTIAL = 206
        const val NOT_FOUND = 404
    }
}
