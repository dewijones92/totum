package com.dewijones92.totum.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldBookTest {
    private var stops = 0
    private val stop: () -> Unit = { stops++ }

    @Test
    fun `a refused start is not counted, so the hold is still released later`() {
        val book = HoldBook()
        runCatching { book.hold(true, start = { error("ForegroundServiceStartNotAllowedException") }, stop = stop) }

        book.hold(true, start = {}, stop = stop)
        book.hold(false, start = {}, stop = stop)
        assertEquals("released at once is deferred while the one real start is pending", 0, stops)
        assertTrue("the started service lets go when it arrives", book.arrived())

        book.hold(false, start = {}, stop = stop)
        assertEquals(1, stops)
    }

    @Test
    fun `a release with nothing pending stops at once`() {
        val book = HoldBook()
        book.hold(true, start = {}, stop = stop)
        assertFalse(book.arrived())
        book.hold(false, start = {}, stop = stop)
        assertEquals(1, stops)
    }
}
