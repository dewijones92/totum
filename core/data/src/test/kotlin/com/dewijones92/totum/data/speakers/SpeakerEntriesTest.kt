package com.dewijones92.totum.data.speakers

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerEntriesTest {

    private fun video(id: String) = PlayableItem(
        MediaItem(MediaItemId(id), SourceId("c"), "Bluey $id", publishedAt = null, duration = null),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    @Test
    fun `a queue with a group travels to the speaker and back unchanged`() {
        val bluey = QueueGroup("shuffle:bluey", "🔀 Bluey")
        val snapshot = QueueSnapshot(
            listOf(QueueEntry(video("aaaaaaaaaaa"), bluey), QueueEntry(video("bbbbbbbbbbb"))),
            1
        )
        val back = snapshot.toSpeakerEntries().toSnapshot(1)
        assertEquals(
            snapshot.entries.map { it.item.item.id to it.group },
            back.entries.map { it.item.item.id to it.group }
        )
        assertEquals(1, back.currentIndex)
        assertEquals(
            PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=aaaaaaaaaaa")),
            back.entries[0].item.handle
        )
    }
}
