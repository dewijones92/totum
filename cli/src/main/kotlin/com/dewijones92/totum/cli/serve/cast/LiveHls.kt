package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.PoliteGate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal fun interface LiveHls {
    fun urlFor(videoId: String): String?
}

internal class CommandOutput(val exit: Int, val out: String, val err: String)

internal class YtDlpLiveHls(
    private val python: String,
    private val jsRuntime: String?,
    private val gate: PoliteGate = PoliteGate(),
    private val botBackOff: BotBackOff = BotBackOff(),
    private val now: () -> Long = System::currentTimeMillis,
    private val exec: (List<String>) -> CommandOutput = ::runCommand,
) : LiveHls {

    private class Kept(val url: String, val validUntilMs: Long)

    private val kept = ConcurrentHashMap<String, Kept>()

    @Volatile var lastFailure: String? = null
        private set

    override fun urlFor(videoId: String): String? {
        lastFailure = null
        if (!VIDEO_ID.matches(videoId)) {
            lastFailure = "'$videoId' is not a YouTube video id"
            Diag.warn("live", "no HLS manifest asked for: $lastFailure")
            return null
        }
        kept[videoId]?.takeIf { it.validUntilMs > now() }?.let {
            Diag.log(
                "live",
                "$videoId: HLS manifest from memory, good for ${(it.validUntilMs - now()) / MS_PER_S}s more"
            )
            return it.url
        }
        val what = "yt-dlp live HLS $videoId"
        if (!botBackOff.allows(what)) {
            lastFailure = "anonymous YouTube is backing off after a bot check"
            return null
        }
        val command = buildList {
            addAll(listOf(python, "-m", "yt_dlp", "-g", "--no-warnings", "-f", "bestaudio/worst"))
            addAll(listOf("--extractor-args", "youtube:player_client=web_embedded"))
            jsRuntime?.let { addAll(listOf("--js-runtimes", it)) }
            add("https://www.youtube.com/watch?v=$videoId")
        }
        val startedAt = now()
        Diag.log("live", "$videoId: asking yt-dlp (web_embedded) for an HLS manifest for a speaker")
        val output = gate.pass(what) { exec(command) }
        val took = now() - startedAt
        val url = output.out.lines().firstOrNull { it.startsWith("http") }?.trim()
        if (output.exit != 0 || url == null) {
            val said = output.err.trim().takeLast(ERROR_CHARS).ifEmpty { "no address printed" }
            lastFailure = "yt-dlp exit ${output.exit}: $said"
            Diag.warn("live", "$videoId: no HLS manifest after ${took}ms: $lastFailure")
            when {
                said.contains("not a bot", ignoreCase = true) -> botBackOff.refused(what)
                TOO_MANY.containsMatchIn(said) -> gate.tooManyRequests(what, retryAfterMs = null)
            }
            return null
        }
        botBackOff.served(what)
        val validUntil = validUntilOf(url)
        kept[videoId] = Kept(url, validUntil)
        Diag.log(
            "live",
            "$videoId: HLS manifest from ${hostOf(url)} after ${took}ms, kept for ${(validUntil - now()) / MS_PER_S}s",
        )
        return url
    }

    private fun validUntilOf(url: String): Long {
        val expiresAtMs = EXPIRE.find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(MS_PER_S)
        return if (expiresAtMs != null) expiresAtMs - EXPIRY_MARGIN_MS else now() + UNKNOWN_EXPIRY_KEPT_MS
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "an unknown host"

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val EXPIRE = Regex("""[/?&]expire[/=](\d{9,11})""")
        val TOO_MANY = Regex("""HTTP Error 429|Too Many Requests""", RegexOption.IGNORE_CASE)
        const val ERROR_CHARS = 300
        const val MS_PER_S = 1_000L
        const val EXPIRY_MARGIN_MS = 10 * 60 * MS_PER_S
        const val UNKNOWN_EXPIRY_KEPT_MS = 30 * 60 * MS_PER_S
    }
}

private const val TIMEOUT_S = 60L

private fun runCommand(command: List<String>): CommandOutput {
    val out = File.createTempFile("totum-live-out", ".txt")
    val err = File.createTempFile("totum-live-err", ".txt")
    return try {
        val process = ProcessBuilder(command).redirectOutput(out).redirectError(err).start()
        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            CommandOutput(-1, "", "took longer than ${TIMEOUT_S}s")
        } else {
            CommandOutput(process.exitValue(), out.readText(), err.readText())
        }
    } catch (e: java.io.IOException) {
        CommandOutput(-1, "", "could not run ${command.first()}: ${e.message}")
    } finally {
        out.delete()
        err.delete()
    }
}
