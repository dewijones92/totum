package com.dewijones92.totum.ytdlp.process

import com.dewijones92.totum.common.Diag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class PythonWorker(
    private val label: String,
    private val command: List<String>,
    private val timeoutMs: Long,
    private val extraEnvironment: Map<String, String> = emptyMap(),
) {
    private val lock = ReentrantLock()
    private val ids = AtomicInteger()
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null
    private var errors: File? = null
    private var starts = 0

    val alive: Boolean get() = lock.withLock { process?.isAlive == true }

    fun ask(args: List<String>): String = lock.withLock {
        val began = System.nanoTime()
        val reply = try {
            exchange(args)
        } catch (e: WorkerGone) {
            if (!e.beforeAnswering) throw e
            Diag.warn("engine", "worker $label was gone before ${args.first()} was sent (${e.message}); restarting it")
            exchange(args)
        }
        Diag.log(
            "engine",
            "worker $label: ${args.first()} answered in ${(System.nanoTime() - began) / NANOS_PER_MS}ms " +
                "(start #$starts, ${reply.length} chars)",
        )
        reply
    }

    fun stop(why: String) = lock.withLock { retire(why) }

    private fun exchange(args: List<String>): String {
        val running = ensureRunning()
        val id = ids.incrementAndGet()
        val line = JsonObject(
            mapOf("id" to JsonPrimitive(id), "args" to JsonArray(args.map(::JsonPrimitive))),
        ).toString()
        try {
            input!!.write(line)
            input!!.newLine()
            input!!.flush()
        } catch (e: IOException) {
            retire("a write failed: ${e.message}")
            throw WorkerGone("${e.message}", beforeAnswering = true)
        }
        val watchdog = Thread {
            try {
                if (!running.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    Diag.warn("engine", "worker $label took longer than ${timeoutMs}ms for ${args.first()}; killing it")
                    running.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                Unit
            }
        }.apply {
            isDaemon = true
            start()
        }
        val answer = try {
            output!!.readLine()
        } finally {
            watchdog.interrupt()
        }
        if (answer == null) {
            val said = errors?.takeIf { it.exists() }?.readText()?.trim()?.takeLast(ERROR_CHARS).orEmpty()
            retire("it stopped answering ${args.first()}")
            throw WorkerGone("the worker died during ${args.first()}: $said", beforeAnswering = false)
        }
        val reply = Json.parseToJsonElement(answer).jsonObject
        if (reply["id"]?.jsonPrimitive?.contentOrNull != id.toString()) {
            retire("it answered out of step: $answer".take(ERROR_CHARS))
            throw WorkerGone("the worker answered out of step", beforeAnswering = false)
        }
        return reply["output"]?.jsonPrimitive?.contentOrNull
            ?: error("the worker's reply had no output: ${answer.take(ERROR_CHARS)}")
    }

    private fun ensureRunning(): Process {
        process?.takeIf { it.isAlive }?.let { return it }
        if (process != null) retire("it had exited")
        starts++
        val log = File.createTempFile("totum-worker-$label", ".err")
        errors = log
        val started = System.nanoTime()
        val launched = runCatching {
            ProcessBuilder(command + "serve")
                .apply { environment().putAll(extraEnvironment) }
                .redirectError(ProcessBuilder.Redirect.appendTo(log))
                .start()
        }.getOrElse { throw IllegalStateException("could not start ${command.first()}: ${it.message}") }
        process = launched
        input = launched.outputStream.bufferedWriter()
        output = launched.inputStream.bufferedReader()
        Diag.log(
            "engine",
            "worker $label started (start #$starts, pid ${launched.pid()}, " +
                "${(System.nanoTime() - started) / NANOS_PER_MS}ms to spawn; errors go to ${log.name})",
        )
        Runtime.getRuntime().addShutdownHook(Thread { launched.destroyForcibly() })
        return launched
    }

    private fun retire(why: String) {
        val gone = process ?: return
        Diag.warn("engine", "worker $label retired: $why (exit ${if (gone.isAlive) "still running" else gone.exitValue()})")
        runCatching { input?.close() }
        gone.destroyForcibly()
        process = null
        input = null
        output = null
    }

    class WorkerGone(message: String, val beforeAnswering: Boolean) : RuntimeException(message)

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val ERROR_CHARS = 500
    }
}
