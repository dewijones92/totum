package com.dewijones92.totum.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The bound on the crash path, and exactly which of these cases guards what.
 *
 * Said precisely because the last attempt at this was not. Against **6f92ecf**, whose drain was
 * unbounded but otherwise correct, the first case below is red — it guards deleting the bound.
 * Against **62f33d9**, which already had the timeout, only the THIRD case is red: that commit
 * caught `TimeoutException` alone, so a read that threw escaped to an outer handler which returned
 * a message and never killed the child. The second case is characterization and guards nothing on
 * its own.
 *
 * A test that cannot fail against the code it replaced is not a guard on it, and saying "written
 * first and seen to fail" of a test that was neither is worse than saying nothing.
 *
 * Deleting the bound makes the first case hang for ever, which is the failure it exists to
 * prevent, so every case carries its own timeout.
 */
class BoundedDrainTest {

    @Test(timeout = HARD_LIMIT_MS)
    fun `a read that never answers is given up on`() {
        val started = CountDownLatch(1)
        val forever = CountDownLatch(1)

        val began = System.nanoTime()
        val answer = drainWithin(seconds = 1, what = "a reader that never returns") {
            started.countDown()
            forever.await()
            "this never arrives"
        }
        val took = (System.nanoTime() - began) / NANOS_PER_MS

        assertNull("a reader that never returns must not be waited on for ever", answer)
        assertTrue("it was started at all", started.await(1, TimeUnit.SECONDS))
        assertTrue("gave up after ${took}ms, which is not the bound it was given", took < HARD_LIMIT_MS)
        forever.countDown()
    }

    @Test(timeout = HARD_LIMIT_MS)
    fun `a read that answers is passed straight through`() {
        assertEquals("the whole tail", drainWithin(seconds = 5, what = "a fast reader") { "the whole tail" })
    }

    /**
     * A throwing read is ALSO "no answer", and must reach the caller's cleanup.
     *
     * It used to fall through to an outer handler that produced a message and never killed the
     * child, so a logcat that failed mid-read left the process behind.
     */
    @Test(timeout = HARD_LIMIT_MS)
    fun `a read that throws is reported as no answer rather than propagating`() {
        assertNull(drainWithin(seconds = 5, what = "a broken reader") { throw IOException("Stream closed") })
    }

    private companion object {
        /** Generous against a loaded machine, and far below "for ever", which is the real failure. */
        const val HARD_LIMIT_MS = 15_000L
        const val NANOS_PER_MS = 1_000_000
    }
}
