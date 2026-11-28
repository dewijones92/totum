package com.dewijones92.totum.ytdlp.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonWorkerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val workers = mutableListOf<PythonWorker>()

    @Before
    fun requirePython() {
        val has = runCatching {
            ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
                .also { it.inputStream.readBytes() }.waitFor() == 0
        }.getOrDefault(false)
        if (System.getenv("CI") != null) check(has) { "CI must have python3" }
        assumeTrue("needs python3 on this machine", has)
    }

    @After
    fun stopWorkers() {
        workers.forEach { it.stop("the test ended") }
    }

    private fun worker(timeoutMs: Long = 20_000): PythonWorker {
        val script = folder.newFile("fake_worker.py").apply { writeText(FAKE_WORKER) }
        return PythonWorker("test", listOf("python3", script.absolutePath), timeoutMs).also { workers += it }
    }

    private fun reply(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `one process answers every request, each with the output for its own id`() {
        val worker = worker()

        val first = reply(worker.ask(listOf("alpha")))
        val second = reply(worker.ask(listOf("beta")))

        assertEquals(first["pid"]?.jsonPrimitive?.int, second["pid"]?.jsonPrimitive?.int)
        assertEquals(1, first["n"]?.jsonPrimitive?.int)
        assertEquals(2, second["n"]?.jsonPrimitive?.int)
        assertEquals("beta", second["cmd"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a worker that dies during a request fails it, and the next ask starts a fresh one`() {
        val worker = worker()
        val before = reply(worker.ask(listOf("alpha")))["pid"]?.jsonPrimitive?.int

        val gone = assertThrows(PythonWorker.WorkerGone::class.java) { worker.ask(listOf("die")) }

        assertFalse("a request the worker received is not re-sent", gone.beforeAnswering)
        val after = reply(worker.ask(listOf("beta")))
        assertNotEquals("the next ask must run in a new process", before, after["pid"]?.jsonPrimitive?.int)
        assertEquals("the new process starts counting again", 1, after["n"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a worker that hangs is killed at the timeout, and the next ask starts a fresh one`() {
        val worker = worker(timeoutMs = 1_500)
        val before = reply(worker.ask(listOf("alpha")))["pid"]?.jsonPrimitive?.int
        val began = System.nanoTime()

        assertThrows(PythonWorker.WorkerGone::class.java) { worker.ask(listOf("hang")) }

        val tookMs = (System.nanoTime() - began) / NANOS_PER_MS
        assertTrue("the hang was cut off at the timeout, not after ${tookMs}ms", tookMs < 15_000)
        val after = reply(worker.ask(listOf("beta")))["pid"]?.jsonPrimitive?.int
        assertNotEquals(before, after)
    }

    @Test
    fun `an answer for another request retires the worker instead of being believed`() {
        val worker = worker()
        val before = reply(worker.ask(listOf("alpha")))["pid"]?.jsonPrimitive?.int

        val gone = assertThrows(PythonWorker.WorkerGone::class.java) { worker.ask(listOf("wrongid")) }

        assertTrue(gone.message.orEmpty(), gone.message.orEmpty().contains("out of step"))
        assertNotEquals(before, reply(worker.ask(listOf("beta")))["pid"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a worker that was killed between requests is replaced and the request is answered`() {
        val worker = worker()
        val before = reply(worker.ask(listOf("alpha")))["pid"]?.jsonPrimitive?.int ?: error("no pid")
        val handle = ProcessHandle.of(before.toLong()).get()
        handle.destroyForcibly()
        handle.onExit().get()

        val after = reply(worker.ask(listOf("beta")))

        assertNotEquals(before, after["pid"]?.jsonPrimitive?.int)
        assertEquals("beta", after["cmd"]?.jsonPrimitive?.content)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L

        val FAKE_WORKER = """
            import json, os, sys, time
            n = 0
            for line in sys.stdin:
                request = json.loads(line)
                n += 1
                cmd = request["args"][0]
                if cmd == "die":
                    os._exit(3)
                if cmd == "hang":
                    time.sleep(120)
                if cmd == "wrongid":
                    print(json.dumps({"id": 987654, "output": "{}"}), flush=True)
                    continue
                output = json.dumps({"pid": os.getpid(), "n": n, "cmd": cmd})
                print(json.dumps({"id": request["id"], "output": output}), flush=True)
        """.trimIndent()
    }
}
