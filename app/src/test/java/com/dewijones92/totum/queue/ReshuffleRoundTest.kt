package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReshuffleRoundTest {

    private val album = QueueGroup("album:a", "Album")
    private val show = QueueGroup("show:s", "Show")

    private fun entry(id: String, group: QueueGroup? = null) = QueueEntry(
        PlayableItem(
            MediaItem(
                id = MediaItemId(id),
                sourceId = SourceId("feed"),
                title = id,
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
            ),
            PlayHandle.Podcast(),
        ),
        group,
    )

    private val tracks = (1..8).map { "a$it" }

    private val queue = QueueSnapshot(
        entries = listOf(entry("x")) + tracks.map { entry(it, album) } + (1..3).map { entry("s$it", show) },
        currentIndex = 8,
    )

    private fun QueueSnapshot.ids() = entries.map { it.item.item.id.value }

    @Test
    fun `a group's next round is the same items in a new order, never starting on the one that just ended`() {
        repeat(20) { seed ->
            val after = queue.reshufflingRun(1, 8, Random(seed), avoidFirst = MediaItemId("a8"))

            assertEquals(tracks.toSet(), after.ids().subList(1, 9).toSet())
            assertNotEquals("a8", after.ids()[1])
            assertEquals(listOf("x"), after.ids().take(1))
            assertEquals(listOf("s1", "s2", "s3"), after.ids().takeLast(3))
            assertEquals("a8", after.current?.item?.item?.id?.value)
        }
        assertNotEquals(tracks, queue.reshufflingRun(1, 8, Random(1), avoidFirst = null).ids().subList(1, 9))
    }

    @Test
    fun `a queue's next round shuffles the groups and the items in each, keeping each group together`() {
        val after = queue.reshufflingEverything(Random(4), avoidFirst = MediaItemId("a8"))

        assertEquals(queue.ids().toSet(), after.ids().toSet())
        assertNotEquals("a new order", queue.ids(), after.ids())
        repeat(20) { seed ->
            assertNotEquals("a8", queue.reshufflingEverything(Random(seed), MediaItemId("a8")).ids().first())
        }
        val runs = after.entries.runs().map { run -> run.first().group?.id }
        assertEquals("each group stays one run", runs.distinct(), runs)
    }

    @Test
    fun `the modes say what they repeat and whether they reshuffle`() {
        assertTrue(RepeatMode.GROUP_SHUFFLED.repeatsGroup && RepeatMode.GROUP_SHUFFLED.reshuffles)
        assertTrue(RepeatMode.QUEUE_SHUFFLED.repeatsQueue && RepeatMode.QUEUE_SHUFFLED.reshuffles)
        assertTrue(RepeatMode.GROUP.repeatsGroup)
        assertFalse(RepeatMode.GROUP.reshuffles || RepeatMode.QUEUE.reshuffles || RepeatMode.ONE.reshuffles)
        assertFalse(RepeatMode.OFF.repeatsGroup || RepeatMode.OFF.repeatsQueue)
    }
}
