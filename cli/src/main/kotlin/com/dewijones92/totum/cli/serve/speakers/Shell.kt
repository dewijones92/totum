package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class ShellOutcome(val out: String?, val failure: String? = null) {
    val said: String get() = out.orEmpty() + failure.orEmpty()
}

internal fun interface Shell {
    suspend fun run(args: List<String>): String?

    suspend fun runKeepingFailure(args: List<String>): ShellOutcome = ShellOutcome(run(args))
}

internal class SystemShell(private val timeoutSeconds: Long = TIMEOUT_S) : Shell {
    override suspend fun run(args: List<String>): String? = runKeepingFailure(args).out

    override suspend fun runKeepingFailure(args: List<String>): ShellOutcome = withContext(Dispatchers.IO) {
        val out = File.createTempFile("totum-shell", ".out")
        val err = File.createTempFile("totum-shell", ".err")
        try {
            val process = ProcessBuilder(args).redirectOutput(out).redirectError(err).start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Diag.warn("speakers", "${args.joinToString(" ")} took over ${timeoutSeconds}s, stopped")
                return@withContext ShellOutcome(null, "timed out after ${timeoutSeconds}s")
            }
            val said = out.readText()
            if (process.exitValue() != 0) {
                val failure = err.readText() + said
                Diag.warn(
                    "speakers",
                    "${args.joinToString(" ")} exit ${process.exitValue()}: ${failure.trim().takeLast(SAID)}",
                )
                ShellOutcome(null, failure)
            } else {
                ShellOutcome(said)
            }
        } catch (e: IOException) {
            Diag.warn("speakers", "${args.firstOrNull()} could not start", e)
            ShellOutcome(null, "could not start: ${e.message}")
        } finally {
            out.delete()
            err.delete()
        }
    }

    private companion object {
        const val TIMEOUT_S = 25L
        const val SAID = 300
    }
}
