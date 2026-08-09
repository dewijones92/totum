package com.dewijones92.totum.ytdlp.process

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The real subprocess plumbing, without needing Python.
 *
 * `/bin/sh` stands in for `python3` — the class only knows "an interpreter and a script", so a
 * shell script exercises every path the real one takes: the arguments, the streams, the exit
 * code and the timeout. What a Python would add is yt-dlp's behaviour, and that is what
 * [LiveExtractionTest] is for.
 */
class SystemCommandRunnerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun runner(body: String, timeoutSeconds: Long = 30) = SystemCommandRunner(
        python = "/bin/sh",
        script = script(body),
        timeoutSeconds = timeoutSeconds,
    )

    private fun script(body: String): File = folder.newFile("bridge-${body.hashCode()}.sh").apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    @Test
    fun `it returns what the script printed on stdout`() = runTest {
        val output = runner("""echo '{"ok": true}'""").run(emptyList())

        assertEquals("""{"ok": true}""", output.trim())
    }

    @Test
    fun `the arguments reach the script in order`() = runTest {
        val output = runner("""echo "$1|$2|$3"""").run(listOf("extract", "https://x", "2"))

        assertEquals("extract|https://x|2", output.trim())
    }

    @Test
    fun `stderr is kept OUT of the answer`() = runTest {
        // yt-dlp warns freely on stderr and the contract is one line of JSON on stdout, so
        // merging the streams would corrupt every response with whatever it was muttering.
        val output = runner("""echo "noise" >&2; echo '{"ok": true}'""").run(emptyList())

        assertEquals("""{"ok": true}""", output.trim())
    }

    @Test
    fun `a failing script raises, carrying its stderr`() = runTest {
        // The exit code alone says nothing anyone can act on; the message on stderr is the
        // whole diagnosis, and it is thrown away on success.
        val thrown = runCatching { runner("""echo "yt-dlp exploded" >&2; exit 3""").run(emptyList()) }

        val message = thrown.exceptionOrNull()?.message.orEmpty()
        assertTrue("should name the exit code: $message", message.contains("3"))
        assertTrue("should carry the reason: $message", message.contains("yt-dlp exploded"))
    }

    @Test
    fun `a script that prints nothing is a failure, not an empty success`() = runTest {
        // Empty output parses as nothing at all downstream, which would surface as a
        // mystifying "no formats" rather than "the extractor did not run".
        val thrown = runCatching { runner("exit 0").run(emptyList()) }

        assertTrue(thrown.isFailure)
    }

    @Test
    fun `a script that hangs is killed rather than hanging the shell`() = runTest {
        val thrown = runCatching { runner("sleep 30", timeoutSeconds = 1).run(emptyList()) }

        assertTrue("it must give up", thrown.isFailure)
        assertTrue(thrown.exceptionOrNull()?.message.orEmpty().contains("longer than"))
    }

    @Test
    fun `a missing interpreter says which one and suggests the fix`() = runTest {
        val missing = SystemCommandRunner(python = "/definitely/not/here", script = script("true"))

        val message = runCatching { missing.run(emptyList()) }.exceptionOrNull()?.message.orEmpty()

        assertTrue("should name it: $message", message.contains("/definitely/not/here"))
        assertTrue("should say what to do: $message", message.contains("Python"))
    }

    @Test
    fun `the bridge script unpacks to a real file that can be run`() {
        val unpacked = BridgeScript.onDisk()

        assertTrue("it must exist on disk for python to run it", unpacked.isFile)
        assertTrue("and be the real script", unpacked.readText().contains("def extract("))
        assertEquals("unpacking twice must not move it", unpacked, BridgeScript.onDisk())
    }
}
