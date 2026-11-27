package com.dewijones92.totum.ytdlp.process

import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.contract.YtDlpEngineContract
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.nio.file.Files
import java.nio.file.Path

class ProcessYtDlpEngineContractTest : YtDlpEngineContract() {

    private lateinit var cache: Path
    private val runners = mutableListOf<WorkerCommandRunner>()

    @Before
    fun requireThePythonSide() {
        val missing = listOfNotNull(
            "python3 with yt-dlp".takeUnless { has("python3", "-c", "import yt_dlp, yt_dlp_ejs") },
            "node".takeUnless { has("node", "--version") },
        )
        if (System.getenv("CI") != null) check(missing.isEmpty()) { "CI must have ${missing.joinToString()}" }
        assumeTrue("needs ${missing.joinToString()} on this machine", missing.isEmpty())
        cache = Files.createTempDirectory("totum-contract-cache")
    }

    @After
    fun stopWorkers() {
        runners.forEach { it.stop("the test ended") }
    }

    override fun newEngine(): YtDlpEngine {
        val runner = WorkerCommandRunner(
            environment = mapOf("XDG_CACHE_HOME" to cache.toString(), "TOTUM_JS_RUNTIME" to "node:${which("node")}"),
        )
        runners += runner
        return ProcessYtDlpEngine(runner)
    }

    private fun has(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0
    }.getOrDefault(false)

    private fun which(name: String): String = System.getenv("PATH").orEmpty().split(':')
        .map { Path.of(it, name) }.first { Files.isExecutable(it) }.toString()
}
