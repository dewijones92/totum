package com.dewijones92.totum.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The crash path's subprocess handling: no hang, no survivor, and a report that explains its gap.
 *
 * None of this was covered. A constant's KDoc even claimed a test that did not exist — there was no
 * `CrashReporter` test in the repo at all (found by an adversarial review, 2026-09-20). The
 * behaviour matters because it runs from the uncaught-exception handler, one line before the report
 * is written: a child that never answers means no report at all, and a child left alive holds a
 * pipe for the rest of the process's life.
 */
class OutputOfTest {

    @Test(timeout = HARD_LIMIT_MS)
    fun `a child that never answers is killed and the gap is explained in the value`() {
        val child = FakeProcess(blocks = true)

        val answer = outputOf({ child }, seconds = 1, what = "logcat", maxChars = 1_000)

        assertEquals(
            "the report has to carry its own explanation — a warning goes to a trail this report " +
                "does not contain",
            unavailable("logcat", 1),
            answer,
        )
        assertTrue("a child that will not answer must not outlive the attempt", child.wasKilled.get())
        child.release()
    }

    @Test(timeout = HARD_LIMIT_MS)
    fun `a child that answers is tidied up rather than left holding descriptors`() {
        val child = FakeProcess(output = "two lines\nof logcat\n")

        val answer = outputOf({ child }, seconds = 5, what = "logcat", maxChars = 1_000)

        assertEquals("two lines\nof logcat\n", answer)
        assertTrue("stdin was left open, and Settings can ask for a report repeatedly", child.stdinClosed)
        assertTrue("the child was left running", child.wasEnded.get())
    }

    @Test(timeout = HARD_LIMIT_MS)
    fun `only the tail is kept, because a report is not a log file`() {
        val child = FakeProcess(output = "0123456789")
        assertEquals("789", outputOf({ child }, seconds = 5, what = "logcat", maxChars = 3))
    }

    /** Failing to START is its own answer, and must not throw on the crash path. */
    @Test(timeout = HARD_LIMIT_MS)
    fun `a child that cannot even start is reported rather than thrown`() {
        val answer = outputOf({ throw IOException("no such binary") }, seconds = 5, what = "logcat", maxChars = 100)
        assertTrue("it must name what went wrong: $answer", answer.contains("IOException"))
    }

    private class FakeProcess(
        private val output: String = "",
        private val blocks: Boolean = false,
    ) : Process() {
        val wasKilled = AtomicBoolean(false)
        val wasEnded = AtomicBoolean(false)
        var stdinClosed = false
            private set

        private val held = CountDownLatch(1)

        private val stdout: InputStream = if (blocks) {
            object : InputStream() {
                override fun read(): Int {
                    held.await()
                    return -1
                }
            }
        } else {
            ByteArrayInputStream(output.toByteArray())
        }

        private val stdin = object : ByteArrayOutputStream() {
            override fun close() {
                stdinClosed = true
            }
        }

        fun release() = held.countDown()

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0

        override fun destroy() {
            wasEnded.set(true)
        }

        override fun destroyForcibly(): Process {
            wasKilled.set(true)
            wasEnded.set(true)
            held.countDown()
            return this
        }
    }

    private companion object {
        const val HARD_LIMIT_MS = 15_000L
    }
}
