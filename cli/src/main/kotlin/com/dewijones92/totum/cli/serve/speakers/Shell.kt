package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun interface Shell {
    suspend fun run(args: List<String>): String?
}

internal class SystemShell(private val timeoutSeconds: Long = TIMEOUT_S) : Shell {
    override suspend fun run(args: List<String>): String? = withContext(Dispatchers.IO) {
        val out = File.createTempFile("totum-shell", ".out")
        val err = File.createTempFile("totum-shell", ".err")
        try {
            val process = ProcessBuilder(args).redirectOutput(out).redirectError(err).start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Diag.warn("speakers", "${args.joinToString(" ")} took over ${timeoutSeconds}s, stopped")
                return@withContext null
            }
            val said = out.readText()
            if (process.exitValue() != 0) {
                Diag.warn(
                    "speakers",
                    "${args.joinToString(
                        " "
                    )} exit ${process.exitValue()}: ${(err.readText() + said).trim().takeLast(SAID)}",
                )
            }
            said.takeIf { process.exitValue() == 0 }
        } catch (e: IOException) {
            Diag.warn("speakers", "${args.firstOrNull()} could not start", e)
            null
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
