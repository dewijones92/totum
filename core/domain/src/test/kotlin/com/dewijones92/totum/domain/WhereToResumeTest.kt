package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhereToResumeTest {

    private fun id(index: Int) = MediaItemId("run:$index")

    @Test
    fun `resuming starts at the item in progress, else the next unplayed, else the first`() {
        val items = (0..3).map(::id)
        assertEquals(
            id(2),
            whereToResume(items, mapOf(id(0) to PlayState.Played, id(2) to PlayState.InProgress(1, null)))
        )
        assertEquals(id(2), whereToResume(items, mapOf(id(0) to PlayState.Played, id(1) to PlayState.Played)))
        assertEquals(id(0), whereToResume(items, emptyMap()))
        assertEquals(id(0), whereToResume(items, items.associateWith { PlayState.Played }))
        assertEquals(id(1), whereToResume(items, mapOf(id(0) to PlayState.Played, id(3) to PlayState.Played)))
    }

    @Test
    fun `an empty run has nowhere to resume`() {
        assertNull(whereToResume(emptyList(), emptyMap()))
    }
}
