package com.dewijones92.totum.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BreadcrumbsKeptLaneTest {

    @Before
    fun clear() = Breadcrumbs.clear()

    @After
    fun clearAfter() = Breadcrumbs.clear()

    private fun messages(tag: String) = Breadcrumbs.snapshot().filter { it.tag == tag }.map { it.message }

    @Test
    fun `the side-quest and cast tags are the ones kept longer`() {
        listOf("Exsurge", "DailyAlarm", "reminders", "cast", "home", "kidlock", "targets").forEach {
            assertTrue(it, it in Breadcrumbs.KEPT_TAGS)
        }
        listOf("playback", "queue", "resolve", "gesture").forEach { assertFalse(it, it in Breadcrumbs.KEPT_TAGS) }
    }

    @Test
    fun `a cast line outlives a flood of playback lines that evicts the playback lines before it`() {
        Breadcrumbs.record("playback", "the first playback line")
        Breadcrumbs.record("cast", "the TV dropped the session (2055)")
        repeat(PLAYBACK_FLOOD) { Breadcrumbs.record("playback", "tick $it") }

        assertEquals(listOf("the TV dropped the session (2055)"), messages("cast"))
        assertFalse("the early playback line was pushed out", "the first playback line" in messages("playback"))
        assertEquals("tick ${PLAYBACK_FLOOD - 1}", messages("playback").last())
    }

    @Test
    fun `the chatty lane is capped at five thousand, the kept lane at a thousand`() {
        repeat(PLAYBACK_FLOOD) { Breadcrumbs.record("playback", "p$it") }
        repeat(1_200) { Breadcrumbs.record("cast", "c$it") }

        assertEquals(5_000, messages("playback").size)
        assertEquals(1_000, messages("cast").size)
        assertEquals("c1199", messages("cast").last())
        assertEquals("c200", messages("cast").first())
    }

    @Test
    fun `the snapshot merges both lanes oldest first, so a report reads as one story`() {
        Breadcrumbs.record("playback", "one")
        Breadcrumbs.record("cast", "two")
        Breadcrumbs.record("playback", "three")
        Breadcrumbs.record("Exsurge", "four")

        assertEquals(listOf("one", "two", "three", "four"), Breadcrumbs.snapshot().map { it.message })
    }

    @Test
    fun `lines restored after a restart are marked, once, and the kept ones go to the kept lane`() {
        val restored = listOf(
            Breadcrumbs.Entry(1_000, "cast", "before the restart A"),
            Breadcrumbs.Entry(2_000, "playback", "before the restart B"),
            Breadcrumbs.Entry(3_000, "cast", "${Breadcrumbs.BEFORE_RESTART}already marked"),
        )

        Breadcrumbs.restore(restored)

        val cast = messages("cast")
        assertEquals(2, cast.size)
        assertEquals(
            setOf(
                "${Breadcrumbs.BEFORE_RESTART}before the restart A",
                "${Breadcrumbs.BEFORE_RESTART}already marked",
            ),
            cast.toSet(),
        )
        assertEquals(listOf("${Breadcrumbs.BEFORE_RESTART}before the restart B"), messages("playback"))
    }

    @Test
    fun `clearing empties both lanes`() {
        Breadcrumbs.record("playback", "p")
        Breadcrumbs.record("cast", "c")

        Breadcrumbs.clear()

        assertTrue(Breadcrumbs.snapshot().isEmpty())
    }

    private companion object {
        const val PLAYBACK_FLOOD = 6_000
    }
}
