package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class DownloadCleanupTest {

    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private val mb = 1024L * 1024

    private fun daysAgo(days: Long) = now.minus(days, ChronoUnit.DAYS)

    private fun candidate(
        id: String,
        sizeBytes: Long = 10 * mb,
        downloadedAt: Instant? = daysAgo(1),
        openedAt: Instant? = null,
        finished: Boolean = false,
        queued: Boolean = true,
    ) = CleanupCandidate(MediaItemId(id), sizeBytes, downloadedAt, openedAt, finished, queued)

    private fun List<CleanupSuggestion>.group(group: CleanupGroup) = firstOrNull { it.group == group }

    private fun List<CleanupSuggestion>.ids(group: CleanupGroup) = group(group)?.ids?.map { it.value }

    @Test
    fun `not opened is counted from the last play or from the download if never played`() {
        val groups = suggestCleanup(
            listOf(
                candidate("played-long-ago", downloadedAt = daysAgo(90), openedAt = daysAgo(40)),
                candidate("played-recently", downloadedAt = daysAgo(90), openedAt = daysAgo(2)),
                candidate("never-played-old", downloadedAt = daysAgo(45), openedAt = null),
                candidate("never-played-new", downloadedAt = daysAgo(3), openedAt = null),
            ),
            now,
        )

        assertEquals(listOf("played-long-ago", "never-played-old"), groups.ids(CleanupGroup.NOT_OPENED))
    }

    @Test
    fun `exactly thirty days counts and a minute short does not`() {
        val groups = suggestCleanup(
            listOf(
                candidate("thirty", downloadedAt = daysAgo(30)),
                candidate("short", downloadedAt = daysAgo(30).plusSeconds(60)),
            ),
            now,
        )

        assertEquals(listOf("thirty"), groups.ids(CleanupGroup.NOT_OPENED))
    }

    @Test
    fun `a download re-fetched after an old play is judged from the newer download`() {
        val groups = suggestCleanup(
            listOf(candidate("refetched", downloadedAt = daysAgo(2), openedAt = daysAgo(60))),
            now,
        )

        assertEquals(null, groups.group(CleanupGroup.NOT_OPENED))
    }

    @Test
    fun `a download with no dates at all is never called unopened`() {
        val groups = suggestCleanup(listOf(candidate("unknown", downloadedAt = null, openedAt = null)), now)

        assertEquals(null, groups.group(CleanupGroup.NOT_OPENED))
    }

    @Test
    fun `finished items form their own group`() {
        val groups = suggestCleanup(listOf(candidate("done", finished = true), candidate("halfway")), now)

        assertEquals(listOf("done"), groups.ids(CleanupGroup.FINISHED))
    }

    @Test
    fun `big means over 500 MB as the screen counts megabytes`() {
        val groups = suggestCleanup(
            listOf(
                candidate("exactly-500", sizeBytes = 500 * mb),
                candidate("just-over", sizeBytes = 500 * mb + 1),
                candidate("two-gig", sizeBytes = 2048 * mb),
            ),
            now,
        )

        assertEquals(listOf("two-gig", "just-over"), groups.ids(CleanupGroup.BIG))
    }

    @Test
    fun `anything not in the queue is suggested`() {
        val groups = suggestCleanup(listOf(candidate("queued"), candidate("gone", queued = false)), now)

        assertEquals(listOf("gone"), groups.ids(CleanupGroup.NOT_QUEUED))
    }

    @Test
    fun `one file can sit in several groups and each group adds up its own bytes`() {
        val file = candidate(
            "everything",
            sizeBytes = 900 * mb,
            downloadedAt = daysAgo(60),
            finished = true,
            queued = false,
        )
        val groups = suggestCleanup(listOf(file, candidate("other", sizeBytes = 600 * mb)), now)

        assertEquals(
            listOf(CleanupGroup.NOT_OPENED, CleanupGroup.FINISHED, CleanupGroup.BIG, CleanupGroup.NOT_QUEUED),
            groups.map { it.group },
        )
        assertEquals(900 * mb, groups.group(CleanupGroup.FINISHED)?.bytes)
        assertEquals(1500 * mb, groups.group(CleanupGroup.BIG)?.bytes)
    }

    @Test
    fun `each group lists its biggest files first`() {
        val groups = suggestCleanup(
            listOf(
                candidate("small", sizeBytes = 5 * mb, finished = true),
                candidate("large", sizeBytes = 50 * mb, finished = true),
                candidate("medium", sizeBytes = 20 * mb, finished = true),
            ),
            now,
        )

        assertEquals(listOf("large", "medium", "small"), groups.ids(CleanupGroup.FINISHED))
    }

    @Test
    fun `nothing to suggest is an empty list, not empty groups`() {
        assertTrue(suggestCleanup(listOf(candidate("fresh")), now).isEmpty())
        assertTrue(suggestCleanup(emptyList(), now).isEmpty())
    }
}
