package com.dewijones92.totum.data.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AQueueEntryRemembersWhenItWasAddedTest {

    private fun item(id: String) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("s"),
            id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$id.mp3")
        ),
        PlayHandle.Podcast(),
    )

    private val monday = Instant.parse("2026-10-12T09:00:00Z")
    private val friday = Instant.parse("2026-10-16T09:00:00Z")

    @Test
    fun `a new entry is stamped, and one that only moved keeps its old date`() {
        val before = QueueSnapshot(listOf(QueueEntry(item("old"), addedAt = monday)))
        val after = QueueSnapshot(listOf(QueueEntry(item("new")), QueueEntry(item("old"))))

        val stamped = after.stampedSince(before, friday)

        assertEquals(listOf(friday, monday), stamped.entries.map { it.addedAt })
    }

    @Test
    fun `a row queued before added dates existed stays undated through later changes`() {
        val before = QueueSnapshot(listOf(QueueEntry(item("old"))))
        val after = QueueSnapshot(listOf(QueueEntry(item("old")), QueueEntry(item("new"))))

        val stamped = after.stampedSince(before, friday)

        assertEquals(listOf(null, friday), stamped.entries.map { it.addedAt })
    }
}
