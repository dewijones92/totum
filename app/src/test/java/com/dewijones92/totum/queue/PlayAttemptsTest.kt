package com.dewijones92.totum.queue

import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayAttemptsTest {

    private fun item(id: String) = MediaItem(
        MediaItemId(id),
        SourceId("s"),
        "title $id",
        publishedAt = null,
        duration = null
    )

    @Test
    fun `a tap shows what is starting until that same attempt ends`() {
        val attempts = PlayAttempts { 0 }
        val first = attempts.begin(item("a"))
        assertEquals("title a", attempts.starting.value?.title)

        attempts.end(first, played = true)
        assertNull(attempts.starting.value)
    }

    @Test
    fun `an older attempt ending late does not clear the newer one`() {
        val attempts = PlayAttempts { 0 }
        val older = attempts.begin(item("a"))
        attempts.begin(item("b"))

        attempts.end(older, played = false)

        assertEquals("title b", attempts.starting.value?.title)
    }

    @Test
    fun `another item becoming current clears a start still waiting`() {
        val attempts = PlayAttempts { 0 }
        attempts.begin(item("a"))

        attempts.supersededBy(MediaItemId("b"))

        assertNull(attempts.starting.value)
    }

    @Test
    fun `the item that was starting becoming current does not clear it early`() {
        val attempts = PlayAttempts { 0 }
        attempts.begin(item("a"))

        attempts.supersededBy(MediaItemId("a"))

        assertEquals("title a", attempts.starting.value?.title)
    }
}
