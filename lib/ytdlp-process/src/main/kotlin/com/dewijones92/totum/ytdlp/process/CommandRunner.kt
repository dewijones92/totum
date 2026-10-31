package com.dewijones92.totum.ytdlp.process

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the bridge script and hands back what it printed.
 *
 * A port rather than a direct `ProcessBuilder` call, so the parsing, the argument shaping and
 * the failure handling can all be tested without a Python on the machine — which is what lets
 * the pyramid have a wide base here instead of one slow live test.
 */
public fun interface CommandRunner {
    public suspend fun run(args: List<String>): String

    public fun lines(args: List<String>): Flow<String> = flow { run(args).lines().forEach { emit(it) } }
}

/**
 * The real one: `python3 totum_ytdlp.py <args…>`.
 *
 * **stdout only.** yt-dlp writes warnings and progress to stderr and the contract is one line of
 * JSON on stdout, so merging the streams would corrupt every response with whatever yt-dlp
 * happened to be muttering.
 */
public class SystemCommandRunner(
    private val python: String = System.getenv("TOTUM_PYTHON") ?: "python3",
    private val script: File = BridgeScript.onDisk(),
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : CommandRunner {

    override suspend fun run(args: List<String>): String = withContext(Dispatchers.IO) {
        val command = listOf(python, script.absolutePath) + args
        Diag.log("engine", "running ${command.take(COMMAND_CHARS).joinToString(" ")}")

        // BOTH streams go to files rather than pipes, for two reasons a test found the hard way:
        //
        //  - reading a pipe blocks, so `readText()` before `waitFor(timeout)` meant the timeout
        //    could never fire and a hung extractor hung the whole tool forever;
        //  - a pipe holds ~64KB, so a chatty stderr fills it and the child blocks writing while
        //    we block reading stdout — a deadlock that only shows up on the noisiest videos.
        //
        // Files have neither problem and cost one temp file per call.
        val out = File.createTempFile("totum-out", ".json")
        val err = File.createTempFile("totum-err", ".txt")
        try {
            val process = runCatching {
                ProcessBuilder(command).redirectOutput(out).redirectError(err).start()
            }.getOrElse { error("could not start $python — is Python 3 installed? (${it.message})") }

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("the extractor took longer than ${timeoutSeconds}s")
            }
            val output = out.readText()
            if (process.exitValue() != 0 || output.isBlank()) {
                // stderr is the only thing that says WHY, and it is thrown away on success — so
                // it is worth every character of it here.
                error(
                    "the extractor failed (exit ${process.exitValue()}): " +
                        err.readText().trim().takeLast(ERROR_CHARS),
                )
            }
            output
        } finally {
            out.delete()
            err.delete()
        }
    }

    override fun lines(args: List<String>): Flow<String> = flow {
        val command = listOf(python, script.absolutePath) + args
        Diag.log("engine", "streaming ${command.take(COMMAND_CHARS).joinToString(" ")}")
        val err = File.createTempFile("totum-err", ".txt")
        val process = ProcessBuilder(command).redirectError(err).start()
        try {
            process.inputStream.bufferedReader().useLines { all -> all.forEach { emit(it) } }
            process.waitFor()
            if (process.exitValue() != 0) {
                val said = err.readText().trim().takeLast(ERROR_CHARS)
                error("the extractor failed (exit ${process.exitValue()}): $said")
            }
        } finally {
            process.destroy()
            err.delete()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        /** Long enough for a slow extraction on a bad connection, short enough not to hang a shell. */
        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val COMMAND_CHARS = 3
        const val ERROR_CHARS = 500
    }
}
