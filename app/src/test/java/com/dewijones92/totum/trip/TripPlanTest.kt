package com.dewijones92.totum.trip

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPlanTest {

    private val show = QueueGroup("feed:show", "A show")
    private val album = QueueGroup("album:x", "An album")

    private fun playable(id: String, url: String? = "https://example.com/$id.mp3") = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("feed"),
            id,
            publishedAt = null,
            duration = null,
            mediaUrl = url?.let(HttpUrl::of),
        ),
        PlayHandle.Podcast(),
    )

    private fun entry(id: String, group: QueueGroup? = show, url: String? = "https://example.com/$id.mp3") =
        QueueEntry(playable(id, url), group)

    private val downloaded = DownloadState.Downloaded("/data/a.mp3")

    private fun inputs(
        entries: List<QueueEntry>,
        perGroup: Int = 2,
        currentIndex: Int = -1,
        states: Map<String, DownloadState> = emptyMap(),
        attempts: Map<String, Int> = emptyMap(),
        pi: List<PiFile> = emptyList(),
        free: Long? = null,
        torrents: Set<String> = emptySet(),
        home: Set<String> = emptySet(),
        played: Set<String> = emptySet(),
    ) = TripInputs(
        queue = QueueSnapshot(entries, currentIndex),
        perGroup = perGroup,
        states = states.mapKeys { MediaItemId(it.key) },
        attempts = attempts.mapKeys { MediaItemId(it.key) },
        piFiles = pi,
        freeBytes = free,
        finished = { it.item.id.value in played },
        fetchesAudioOnly = { it.item.id.value !in torrents },
        atHome = { it.item.id.value in home },
    )

    private fun ids(items: List<PlayableItem>) = items.map { it.item.id.value }

    private val queue = listOf(
        entry("e1"),
        entry("e2"),
        entry("e3"),
        entry("s1", album),
        entry("s2", album),
        entry("s3", album),
        entry("u1", null),
        entry("u2", null),
        entry("u3", null),
    )

    @Test
    fun `the next few of every group, and of the ungrouped run, are wanted`() {
        val plan = planTrip(inputs(queue))

        assertEquals(listOf("e1", "e2", "s1", "s2", "u1", "u2"), ids(plan.wanted))
        assertEquals(ids(plan.wanted), ids(plan.fetch))
        assertEquals(0, plan.onPhone)
    }

    @Test
    fun `a group that is playing starts after what is playing`() {
        val plan = planTrip(inputs(queue, currentIndex = 1))

        assertEquals(listOf("e3", "s1", "s2", "u1", "u2"), ids(plan.wanted))
    }

    @Test
    fun `played items are passed over`() {
        val plan = planTrip(inputs(queue, played = setOf("e1", "s1")))

        assertEquals(listOf("e2", "e3", "s2", "s3", "u1", "u2"), ids(plan.wanted))
    }

    @Test
    fun `what is on the phone counts, and what is on the way is not asked for twice`() {
        val plan = planTrip(
            inputs(
                queue,
                states = mapOf("e1" to downloaded, "e2" to DownloadState.Downloading(5, 10)),
            ),
        )

        assertEquals(1, plan.onPhone)
        assertEquals(1, plan.fetching)
        assertFalse("e1" in ids(plan.fetch))
        assertFalse("e2" in ids(plan.fetch))
        assertTrue("s1" in ids(plan.fetch))
    }

    @Test
    fun `a torrent is skipped with its reason and never fetched`() {
        val plan = planTrip(inputs(queue, torrents = setOf("e1")))

        assertFalse("e1" in ids(plan.fetch))
        assertEquals(listOf("e1"), plan.skipped.map { it.item.item.id.value })
        assertTrue(plan.skipped.single().why.contains("download you ask for"))
    }

    @Test
    fun `an item with nothing to fetch yet is skipped`() {
        val plan = planTrip(inputs(listOf(entry("n1", url = null))))

        assertTrue(plan.fetch.isEmpty())
        assertEquals(1, plan.skipped.size)
    }

    @Test
    fun `a failure that cannot succeed, or has had its goes, is left alone, and a fresh one is retried`() {
        val plan = planTrip(
            inputs(
                queue,
                perGroup = 3,
                states = mapOf(
                    "e1" to DownloadState.Failed("HTTP Error 403: members-only video"),
                    "e2" to DownloadState.Failed("socket timeout"),
                    "e3" to DownloadState.Failed("socket timeout"),
                ),
                attempts = mapOf("e2" to MAX_ATTEMPTS, "e3" to 1),
            ),
        )

        assertEquals(listOf("e1", "e2"), plan.skipped.map { it.item.item.id.value })
        assertEquals(2, plan.failed)
        assertTrue("e3" in ids(plan.fetch))
    }

    @Test
    fun `a queued item the Pi has finished is copied from the Pi, not fetched from the web`() {
        val plan = planTrip(inputs(queue, home = setOf("e1")))

        assertEquals(listOf(MediaItemId("e1")), plan.copyFromPi)
        assertFalse("e1" in ids(plan.fetch))
    }

    @Test
    fun `other files the Pi has ready are copied while there is room, and not the ones already queued`() {
        val gb = 1024L * 1024 * 1024
        val pi = listOf(
            PiFile(MediaItemId("e1"), "queued", gb),
            PiFile(MediaItemId("f1"), "a", 3 * gb),
            PiFile(MediaItemId("f2"), "b", 3 * gb),
        )

        val plan = planTrip(inputs(queue, pi = pi, home = setOf("e1"), free = 7 * gb))

        assertEquals(listOf("f1"), plan.piExtras.map { it.id.value })
    }

    @Test
    fun `a Pi file already on the phone or on its way is not copied again`() {
        val gb = 1024L * 1024 * 1024
        val pi = listOf(PiFile(MediaItemId("f1"), "a", gb), PiFile(MediaItemId("f2"), "b", gb))

        val plan = planTrip(
            inputs(
                emptyList(),
                pi = pi,
                states = mapOf("f1" to downloaded, "f2" to DownloadState.Downloading(1, 10)),
            ),
        )

        assertTrue(plan.piExtras.isEmpty())
    }

    @Test
    fun `under the reserve nothing is fetched or copied and the plan says so`() {
        val gb = 1024L * 1024 * 1024
        val plan = planTrip(
            inputs(queue, home = setOf("e1"), pi = listOf(PiFile(MediaItemId("f1"), "a", gb)), free = gb),
        )

        assertTrue(plan.lowSpace)
        assertTrue(plan.fetch.isEmpty())
        assertTrue(plan.copyFromPi.isEmpty())
        assertTrue(plan.piExtras.isEmpty())
        assertEquals(6, plan.wanted.size)
    }

    @Test
    fun `an empty queue wants nothing`() {
        val plan = planTrip(inputs(emptyList()))

        assertTrue(plan.wanted.isEmpty())
        assertTrue(plan.fetch.isEmpty())
    }

    @Test
    fun `a Pi copy that has failed twice is left alone, one that failed once is tried again`() {
        val failed = DownloadState.Failed("the copy broke")
        val twice = planTrip(
            inputs(queue, home = setOf("e1"), states = mapOf("e1" to failed), attempts = mapOf("e1" to MAX_ATTEMPTS)),
        )
        val once = planTrip(
            inputs(queue, home = setOf("e1"), states = mapOf("e1" to failed), attempts = mapOf("e1" to 1)),
        )

        assertTrue(twice.copyFromPi.isEmpty())
        assertEquals(listOf("e1"), twice.skipped.map { it.item.item.id.value })
        assertEquals(1, twice.failed)
        assertEquals(listOf("e1"), once.copyFromPi.map { it.value })
    }

    @Test
    fun `other Pi files that have failed twice are not asked for again`() {
        val failed = DownloadState.Failed("the copy broke")
        val files = listOf(PiFile(MediaItemId("f1"), "a", 10), PiFile(MediaItemId("f2"), "b", 10))
        val plan = planTrip(
            inputs(
                queue,
                pi = files,
                states = mapOf("f1" to failed, "f2" to failed),
                attempts = mapOf("f1" to MAX_ATTEMPTS, "f2" to 1),
            ),
        )

        assertEquals(listOf("f2"), plan.piExtras.map { it.id.value })
    }
}
