package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.Pipeline
import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.sound.SoundJob
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.cast.CastHttpServer
import com.dewijones92.totum.playback.cast.HttpReply
import com.dewijones92.totum.playback.cast.HttpRequest
import com.dewijones92.totum.playback.cast.LocalRanges
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal class SoundStreams(
    private val processed: ProcessedSound,
    private val base: String?,
    private val port: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Offer(
        val label: String,
        val url: String,
        val sound: SoundSettings,
        val startAtMs: Long,
        val madeAtMs: Long,
        @Volatile var running: Pipeline? = null,
    )

    private val offers = ConcurrentHashMap<String, Offer>()
    private val files = ConcurrentHashMap<String, File>()
    private val server = CastHttpServer(::handle)

    val available: Boolean get() = base != null

    fun offer(label: String, url: String, sound: SoundSettings, startAtMs: Long): String? {
        val where = base ?: return null
        server.start(port)
        prune()
        val token = tokenOf()
        offers[token] = Offer(label, url, sound, startAtMs, clock())
        Diag.log("cast-target", "$label: processed sound offered at /s/${token.take(TOKEN_SHOWN)}… sound=$sound")
        return "${where.trimEnd('/')}/s/$token.aac"
    }

    fun offerFile(label: String, file: File): String? {
        val where = base ?: return null
        server.start(port)
        val token = tokenOf()
        files[token] = file
        Diag.log("cast-target", "$label: ${file.name} offered at /f/${token.take(TOKEN_SHOWN)}…")
        return "${where.trimEnd('/')}/f/$token.${file.extension.ifEmpty { "bin" }}"
    }

    internal fun offered(): Int = offers.size

    private fun prune() {
        val now = clock()
        offers.entries.removeIf { (token, offer) ->
            val unused = offer.running == null && now - offer.madeAtMs > UNUSED_OFFER_MS
            val finished = offer.running?.wanted == false
            (unused || finished).also {
                if (it) Diag.log("cast-target", "${offer.label}: offer ${token.take(TOKEN_SHOWN)}… expired")
            }
        }
    }

    fun skippedMs(url: String): Long = tokenIn(url)?.let(offers::get)?.running?.skippedMs ?: 0

    fun withdraw(url: String) {
        val token = tokenIn(url)
        if (token != null) offers.remove(token)?.running?.stop()
        FILE.find(url)?.groupValues?.get(1)?.let(files::remove)
    }

    private fun handle(request: HttpRequest): HttpReply {
        FILE.matchEntire(request.path)?.groupValues?.get(1)?.let(files::get)?.let { file ->
            Diag.log("cast-target", "${file.name}: ${request.from} fetches ${request.header("range") ?: "all of it"}")
            return LocalRanges.reply(file, request.header("range"))
        }
        val token = TOKEN.matchEntire(request.path)?.groupValues?.get(1)
        val offer = token?.let(offers::get)
        if (offer == null) {
            Diag.warn(
                "cast-target",
                "sound stream ${request.method} ${request.path} from ${request.from}: no such offer"
            )
            return HttpReply.Bytes(NOT_FOUND, "text/plain", ByteArray(0))
        }
        Diag.log("cast-target", "${offer.label}: ${request.from} fetches the processed sound")
        return HttpReply.Stream(OK, TYPE, null, mapOf("Cache-Control" to "no-store")) { open(offer) }
    }

    private fun open(offer: Offer): InputStream {
        offer.running?.stop()
        val pipeline = processed.toAac(SoundJob(offer.label, offer.url, offer.sound, offer.startAtMs))
        offer.running = pipeline
        return pipeline.output ?: InputStream.nullInputStream()
    }

    private fun tokenIn(url: String): String? = TOKEN.find(
        url.substringAfter("/s/").let { "/s/$it" }
    )?.groupValues?.get(1)

    private fun tokenOf(): String = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val TYPE = "audio/aac"
        const val TOKEN_BYTES = 16
        const val TOKEN_SHOWN = 6
        const val UNUSED_OFFER_MS = 10 * 60 * 1_000L
        val TOKEN = Regex("""/s/([0-9a-f]{32})\.aac""")
        val FILE = Regex("""/f/([0-9a-f]{32})\.\w+""")
    }
}
