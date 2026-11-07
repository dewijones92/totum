package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.cli.serve.sound.Pipeline
import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.sound.SOUND_CHANNELS
import com.dewijones92.totum.cli.serve.sound.SOUND_RATE
import com.dewijones92.totum.cli.serve.sound.SoundJob
import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.cli.serve.targets.TargetPlayer
import com.dewijones92.totum.cli.serve.targets.withoutApiKeys
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.cast.LocalManifests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class MpvPlayer(
    private val id: String,
    private val dir: File,
    private val mpv: String = System.getenv("TOTUM_MPV") ?: "mpv",
    private val processed: ProcessedSound = ProcessedSound(),
    private val locally: (label: String, url: String) -> String = { _, url -> url },
) : TargetPlayer {
    private val socket = File(dir, "mpv-$id.sock")

    @Volatile
    private var process: Process? = null

    override var onEnded: (failed: Boolean) -> Unit = {}

    @Volatile
    private var pipeline: Pipeline? = null

    @Volatile
    private var pipelineFailed = false

    @Volatile
    private var offsetMs = 0L

    @Volatile
    private var chosenVolume: Int? = null

    @Volatile
    override var failure: String? = null
        private set

    override fun play(request: PlayRequest) {
        stop()
        dir.mkdirs()
        socket.delete()
        val log = File(dir, "mpv-$id.log")
        val piped = request.sound.changesSound
        val address = locally(id, request.url)
        val live = LocalManifests.isLive(request.url) || LocalManifests.isLive(address)
        val startAt = if (live) emptyList() else listOf("--start=${request.startAtMs / MS_PER_S.toDouble()}")
        val source = if (piped) PIPED_SOURCE else startAt + listOf("--", address)
        val command = listOf(
            mpv, "--no-video", "--no-input-terminal", "--msg-level=all=warn", "--term-status-msg=", "--idle=no",
            "--audio-device=pulse/${request.destination}", "--input-ipc-server=${socket.path}",
        ) + listOfNotNull(chosenVolume?.let { "--volume=$it" }) + source
        val started = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        process = started
        pipelineFailed = false
        failure = null
        offsetMs = request.startAtMs
        pipeline = if (piped) {
            processed.toSink(
                SoundJob(id, address, request.sound, request.startAtMs),
                started.outputStream,
            ) { failed ->
                if (failed) pipelineFailed = true
            }
        } else {
            runCatching { started.outputStream.close() }
            null
        }
        Diag.log(
            "speakers",
            "$id: mpv ${started.pid()} on ${request.destination} volume=${chosenVolume ?: "untouched"} " +
                "${sourceKind(request.url, address)}${if (live) " live" else ""} sound=${request.sound} " +
                "from=${if (live) "the live edge" else "${request.startAtMs}ms"}",
        )
        thread(name = "mpv-$id", isDaemon = true) {
            val code = started.waitFor()
            if (process === started) {
                process = null
                pipeline?.stop()
                pipeline = null
                val tail = runCatching { log.readLines().takeLast(LOG_LINES).joinToString(" | ") }.getOrDefault("")
                    .withoutApiKeys()
                val failed = code != 0 || pipelineFailed
                failure = if (failed) failureOf(code, pipelineFailed, tail) else null
                Diag.log(
                    "speakers",
                    "$id: mpv ${started.pid()} ended, exit $code pipelineFailed=$pipelineFailed" +
                        if (failed) ": $tail" else ""
                )
                onEnded(failed)
            }
        }
    }

    private fun failureOf(code: Int, pipelineFailed: Boolean, tail: String): String =
        "the player exited with code $code" + (if (pipelineFailed) " and the sound pipeline failed" else "") +
            (if (tail.isNotBlank()) ": ${tail.takeLast(FAILURE_CHARS)}" else "")

    private fun sourceKind(url: String, address: String): String = when {
        url.startsWith("/") -> "file"
        address != url -> "relayed stream"
        else -> "stream"
    }

    override fun pause(paused: Boolean) {
        ipc(JsonPrimitive("set_property"), JsonPrimitive("pause"), JsonPrimitive(paused))
    }

    override fun seek(positionMs: Long) {
        ipc(JsonPrimitive("seek"), JsonPrimitive(positionMs / MS_PER_S.toDouble()), JsonPrimitive("absolute"))
    }

    override val volumePercent: Int? get() = chosenVolume

    override fun volume(percent: Int) {
        chosenVolume = percent
        ipc(JsonPrimitive("set_property"), JsonPrimitive("volume"), JsonPrimitive(percent))
    }

    override fun stop() {
        val playing = process ?: return
        process = null
        pipeline?.stop()
        pipeline = null
        playing.destroy()
        if (!playing.waitFor(STOP_WAIT_S, TimeUnit.SECONDS)) playing.destroyForcibly()
        Diag.log("speakers", "$id: mpv ${playing.pid()} stopped")
    }

    override val positionMs: Long? get() = seconds("time-pos")?.let { offsetMs + it + (pipeline?.skippedMs ?: 0) }

    override val durationMs: Long? get() = if (pipeline == null) seconds("duration") else null

    private fun seconds(property: String): Long? = process?.let {
        ipc(
            JsonPrimitive("get_property"),
            JsonPrimitive(property)
        )?.jsonPrimitive?.doubleOrNull?.let { s -> (s * MS_PER_S).toLong() }
    }

    private fun ipc(vararg command: JsonElement): JsonElement? {
        if (process == null || !socket.exists()) return null
        return try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socket.toPath()))
                val request = buildJsonObject { put("command", JsonArray(command.toList())) }.toString() + "\n"
                channel.write(ByteBuffer.wrap(request.toByteArray()))
                answerOf(channel)
            }
        } catch (e: IOException) {
            Diag.warn("speakers", "$id: mpv did not answer ${command.firstOrNull()}: $e")
            null
        }
    }

    private fun answerOf(channel: SocketChannel): JsonElement? {
        channel.configureBlocking(false)
        Selector.open().use { selector ->
            channel.register(selector, SelectionKey.OP_READ)
            val buffer = ByteBuffer.allocate(BUFFER)
            val text = StringBuilder()
            val deadline = System.currentTimeMillis() + ANSWER_MS
            var open = true
            while (open && System.currentTimeMillis() < deadline && selector.select(ANSWER_MS) > 0) {
                selector.selectedKeys().clear()
                buffer.clear()
                val read = channel.read(buffer)
                open = read >= 0
                if (open) text.append(String(buffer.array(), 0, read))
                val answer = text.lines().mapNotNull { line ->
                    runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                }.firstOrNull { "error" in it }
                if (answer != null) return answer["data"]
            }
        }
        return null
    }

    private companion object {
        const val MS_PER_S = 1_000
        val PIPED_SOURCE = listOf(
            "--demuxer=rawaudio",
            "--demuxer-rawaudio-format=s16le",
            "--demuxer-rawaudio-rate=$SOUND_RATE",
            "--demuxer-rawaudio-channels=$SOUND_CHANNELS",
            "-",
        )
        const val STOP_WAIT_S = 2L
        const val ANSWER_MS = 500L
        const val BUFFER = 4_096
        const val LOG_LINES = 4
        const val FAILURE_CHARS = 300
    }
}
