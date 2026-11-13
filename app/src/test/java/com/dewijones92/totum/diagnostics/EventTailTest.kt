package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.Diag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventTailTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun clear() = Breadcrumbs.clear()

    @After
    fun clearAgain() = Breadcrumbs.clear()

    @Test
    fun `the last events survive a restart, marked as from before it`() {
        val file = tmp.newFile("tail.json")
        Diag.log("home", "job started")
        Diag.log("home", "the 15:17 event")
        assertTrue(EventTail(file).save())

        Breadcrumbs.clear()
        Diag.log("queue", "after the restart")
        EventTail(file).restore()

        val messages = Breadcrumbs.snapshot().map { it.message }
        assertTrue(messages.toString(), "${Breadcrumbs.BEFORE_RESTART}the 15:17 event" in messages)
        val marked = messages.indexOf("${Breadcrumbs.BEFORE_RESTART}the 15:17 event")
        assertTrue(messages.toString(), messages.indexOf("after the restart") > marked)
    }

    @Test
    fun `only the newest events are kept, and an unchanged tail is not rewritten`() {
        val file = tmp.newFile("tail.json")
        repeat(10) { Diag.log("home", "event $it") }
        val tail = EventTail(file, keep = 3)

        assertTrue(tail.save())
        assertFalse("nothing new, so nothing written", tail.save())

        Breadcrumbs.clear()
        EventTail(file).restore()
        val restored = Breadcrumbs.snapshot().map { it.message }.filter { it.startsWith(Breadcrumbs.BEFORE_RESTART) }
        assertEquals(
            listOf("event 7", "event 8", "event 9").map { Breadcrumbs.BEFORE_RESTART + it },
            restored,
        )
    }

    @Test
    fun `a missing or damaged file restores nothing and does not throw`() {
        EventTail(tmp.root.resolve("absent.json")).restore()
        val damaged = tmp.newFile("damaged.json").apply { writeText("{not json") }

        EventTail(damaged).restore()

        assertTrue(Breadcrumbs.snapshot().none { it.message.startsWith(Breadcrumbs.BEFORE_RESTART) })
    }
}
