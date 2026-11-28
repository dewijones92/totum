package com.dewijones92.totum.ytdlp.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

public class WorkerCommandRunner(
    private val python: String = System.getenv("TOTUM_PYTHON") ?: "python3",
    private val script: File = BridgeScript.onDisk(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val streaming: CommandRunner = SystemCommandRunner(python, script),
    environment: Map<String, String> = emptyMap(),
) : CommandRunner {

    private val command = listOf(python, script.absolutePath)
    private val solver = PythonWorker("solver", command, timeoutMs, environment)
    private val general = PythonWorker("general", command, timeoutMs, environment)

    override suspend fun run(args: List<String>): String = withContext(Dispatchers.IO) {
        val worker = if (args.first() in SOLVER_COMMANDS) solver else general
        worker.ask(args)
    }

    override fun lines(args: List<String>): Flow<String> = streaming.lines(args)

    override suspend fun warm(): String = coroutineScope {
        val both = listOf(solver, general).map { worker -> async(Dispatchers.IO) { worker.ask(listOf("warm")) } }
        both.map { it.await() }.first()
    }

    public fun stop(why: String) {
        solver.stop(why)
        general.stop(why)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        val SOLVER_COMMANDS = setOf("solve_n", "solve_sig", "warm", "prepare_player", "state")
    }
}
