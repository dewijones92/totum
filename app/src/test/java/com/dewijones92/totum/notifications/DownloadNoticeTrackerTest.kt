package com.dewijones92.totum.notifications

import com.dewijones92.totum.data.download.DownloadEvent
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class DownloadNoticeTrackerTest {

    private val tracker = DownloadNoticeTracker()

    private fun item(id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = "Episode $id",
        publishedAt = null,
        duration = 30.minutes,
    )

    /** [percent] of a notional 100-byte download, so intent reads clearly in the tests. */
    private fun downloading(id: String, percent: Long? = null) =
        tracker.onEvent(DownloadEvent(item(id), DownloadState.Downloading(percent ?: 0, percent?.let { 100 })))

    private fun downloaded(id: String) =
        tracker.onEvent(DownloadEvent(item(id), DownloadState.Downloaded("/tmp/$id.media")))

    private fun failed(id: String, reason: String = "network") =
        tracker.onEvent(DownloadEvent(item(id), DownloadState.Failed(reason)))

    @Test
    fun `running downloads each have their own progress, and queued ones are only counted`() {
        downloading("a", percent = 40)
        downloading("b", percent = 10)
        val notice = downloading("c")

        assertEquals(listOf("Episode a" to 40, "Episode b" to 10), notice.running.map { it.item.title to it.percent })
        assertEquals(1, notice.waiting)
        assertEquals(3, notice.active.size)
    }

    @Test
    fun `a starting download becomes active`() {
        val notice = downloading("a")

        assertEquals(listOf("Episode a"), notice.active.map { it.title })
        assertTrue(notice.completed.isEmpty())
    }

    @Test
    fun `finishing moves an item from active to completed`() {
        downloading("a")

        val notice = downloaded("a")

        assertTrue(notice.active.isEmpty())
        assertEquals(listOf("Episode a"), notice.completed.map { it.title })
    }

    @Test
    fun `failing moves an item to failed with its reason`() {
        downloading("a")

        val notice = failed("a", "HTTP 404")

        assertTrue(notice.active.isEmpty())
        assertEquals("HTTP 404", notice.failed.single().reason)
    }

    /** Progress across a batch is aggregated, since there is one notification for it. */
    @Test
    fun `percent averages the active downloads`() {
        downloading("a", percent = 20)

        val notice = downloading("b", percent = 80)

        assertEquals(50, notice.percent)
    }

    /**
     * A determinate bar built from partial information lies. Podcast enclosures often
     * arrive without a content length, so this has to stay indeterminate.
     */
    @Test
    fun `percent is unknown while any active download has no fraction`() {
        downloading("a", percent = 50)

        val notice = downloading("b", percent = null)

        assertNull(notice.percent)
    }

    /**
     * The completed list must not grow for the whole session — a new batch starting is
     * the natural point to forget the last one's results.
     */
    @Test
    fun `a new batch clears the previous batch's results`() {
        downloading("a")
        downloaded("a")
        failed("b")

        val notice = downloading("c")

        assertTrue(notice.completed.isEmpty())
        assertTrue(notice.failed.isEmpty())
        assertEquals(listOf("Episode c"), notice.active.map { it.title })
    }

    /** Within one batch, results accumulate so the shade lists everything that finished. */
    @Test
    fun `results accumulate while a batch is still running`() {
        downloading("a")
        downloading("b")
        downloaded("a")

        val notice = downloaded("b")

        assertEquals(listOf("Episode a", "Episode b"), notice.completed.map { it.title })
    }

    /** A delete, or the startup reset of an interrupted download, is not news. */
    @Test
    fun `NotDownloaded clears an item without reporting it`() {
        downloading("a")

        val notice = tracker.onEvent(DownloadEvent(item("a"), DownloadState.NotDownloaded))

        assertTrue(notice.isIdle)
    }

    /** Retrying something that failed must clear the old failure, not show both. */
    @Test
    fun `restarting a failed download drops the failure`() {
        downloading("a")
        failed("a")

        val notice = downloading("a")

        assertTrue(notice.failed.isEmpty())
        assertEquals(listOf("Episode a"), notice.active.map { it.title })
    }

    @Test
    fun `progress updates to the same item do not duplicate it`() {
        downloading("a", percent = 10)

        val notice = downloading("a", percent = 90)

        assertEquals(1, notice.active.size)
        assertEquals(90, notice.percent)
    }
}
