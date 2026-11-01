package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.common.Diag
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
) : SpeakerPlayer {
    private val socket = File(dir, "mpv-$id.sock")

    @Volatile
    private var process: Process? = null

    override var onEnded: (failed: Boolean) -> Unit = {}

    override fun play(url: String, sink: String, volume: Int) {
        stop()
        dir.mkdirs()
        socket.delete()
        val log = File(dir, "mpv-$id.log")
        val started = ProcessBuilder(
            mpv, "--no-video", "--no-terminal", "--idle=no", "--audio-device=pulse/$sink", "--volume=$volume",
            "--input-ipc-server=${socket.path}", "--", url,
        ).redirectErrorStream(true).redirectOutput(log).start()
        process = started
        Diag.log(
            "speakers",
            "$id: mpv ${started.pid()} on $sink volume=$volume ${if (url.startsWith("/")) "file" else "stream"}"
        )
        thread(name = "mpv-$id", isDaemon = true) {
            val code = started.waitFor()
            if (process === started) {
                process = null
                val tail = runCatching { log.readLines().takeLast(LOG_LINES).joinToString(" | ") }.getOrDefault("")
                Diag.log("speakers", "$id: mpv ${started.pid()} ended, exit $code${if (code == 0) "" else ": $tail"}")
                onEnded(code != 0)
            }
        }
    }

    override fun pause(paused: Boolean) {
        ipc(JsonPrimitive("set_property"), JsonPrimitive("pause"), JsonPrimitive(paused))
    }

    override fun seek(positionMs: Long) {
        ipc(JsonPrimitive("seek"), JsonPrimitive(positionMs / MS_PER_S.toDouble()), JsonPrimitive("absolute"))
    }

    override fun volume(percent: Int) {
        ipc(JsonPrimitive("set_property"), JsonPrimitive("volume"), JsonPrimitive(percent))
    }

    override fun stop() {
        val playing = process ?: return
        process = null
        playing.destroy()
        if (!playing.waitFor(STOP_WAIT_S, TimeUnit.SECONDS)) playing.destroyForcibly()
        Diag.log("speakers", "$id: mpv ${playing.pid()} stopped")
    }

    override val positionMs: Long? get() = seconds("time-pos")

    override val durationMs: Long? get() = seconds("duration")

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
        const val STOP_WAIT_S = 2L
        const val ANSWER_MS = 500L
        const val BUFFER = 4_096
        const val LOG_LINES = 3
    }
}
