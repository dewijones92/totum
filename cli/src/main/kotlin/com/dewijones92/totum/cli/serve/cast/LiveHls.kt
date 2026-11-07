package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import java.io.File
import java.util.concurrent.TimeUnit

internal fun interface LiveHls {
    fun urlFor(videoId: String): String?
}

internal class CommandOutput(val exit: Int, val out: String, val err: String)

internal class YtDlpLiveHls(
    private val python: String,
    private val jsRuntime: String?,
    private val exec: (List<String>) -> CommandOutput = ::runCommand,
) : LiveHls {

    @Volatile var lastFailure: String? = null
        private set

    override fun urlFor(videoId: String): String? {
        lastFailure = null
        if (!VIDEO_ID.matches(videoId)) {
            lastFailure = "'$videoId' is not a YouTube video id"
            Diag.warn("live", "no HLS manifest asked for: $lastFailure")
            return null
        }
        val command = buildList {
            addAll(listOf(python, "-m", "yt_dlp", "-g", "--no-warnings", "-f", "bestaudio/worst"))
            addAll(listOf("--extractor-args", "youtube:player_client=web_embedded"))
            jsRuntime?.let { addAll(listOf("--js-runtimes", it)) }
            add("https://www.youtube.com/watch?v=$videoId")
        }
        val startedAt = System.currentTimeMillis()
        Diag.log("live", "$videoId: asking yt-dlp (web_embedded) for an HLS manifest for a speaker")
        val output = exec(command)
        val took = System.currentTimeMillis() - startedAt
        val url = output.out.lines().firstOrNull { it.startsWith("http") }?.trim()
        if (output.exit != 0 || url == null) {
            val said = output.err.trim().takeLast(ERROR_CHARS).ifEmpty { "no address printed" }
            lastFailure = "yt-dlp exit ${output.exit}: $said"
            Diag.warn("live", "$videoId: no HLS manifest after ${took}ms: $lastFailure")
            return null
        }
        Diag.log("live", "$videoId: HLS manifest from ${hostOf(url)} after ${took}ms")
        return url
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "an unknown host"

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        const val ERROR_CHARS = 300
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
