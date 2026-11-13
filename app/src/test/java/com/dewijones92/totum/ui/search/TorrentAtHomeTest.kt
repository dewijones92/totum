package com.dewijones92.totum.ui.search

import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomeRetry
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentAtHomeTest {
    private val film =
        BackupItem("torrent:2db6:1", "torrent", "Toy Story 5", contentKind = "STANDARD", playbackType = "VIDEO")
    private fun job(id: String, state: HomeJobState, bytes: Long = 0) =
        HomeJob(id, film.copy(itemId = id), state, false, bytes, 1_000)
    private val nothingOnPhone: (MediaItemId) -> DownloadState = { DownloadState.NotDownloaded }

    @Test
    fun `a film deleted from the phone but at home offers the copy back`() {
        val jobs = listOf(job("torrent:2db6:1", HomeJobState.DONE)).associateBy { MediaItemId(it.id) }
        assertEquals(
            TorrentAtHome.Ready(listOf(MediaItemId("torrent:2db6:1"))),
            torrentAtHome("2DB6", jobs, nothingOnPhone)
        )
    }

    @Test
    fun `on the phone too it only says so, and while downloading it gives the percentage`() {
        val done = listOf(job("torrent:2db6:1", HomeJobState.DONE)).associateBy { MediaItemId(it.id) }
        assertEquals(TorrentAtHome.AlsoOnPhone, torrentAtHome("2db6", done) { DownloadState.Downloaded("/x.mkv") })
        val running = listOf(
            job("torrent:2db6:1", HomeJobState.DOWNLOADING, bytes = 450)
        ).associateBy { MediaItemId(it.id) }
        assertEquals(TorrentAtHome.Downloading(45), torrentAtHome("2db6", running, nothingOnPhone))
    }

    @Test
    fun `extras done at home do not make the film ready, the film does`() {
        val extra = MediaItemId("torrent:2db6:2")
        val extras = setOf(extra)
        val filmRunning = listOf(
            job("torrent:2db6:1", HomeJobState.DOWNLOADING, bytes = 80),
            job("torrent:2db6:2", HomeJobState.DONE),
        ).associateBy { MediaItemId(it.id) }
        assertEquals(TorrentAtHome.Downloading(8), torrentAtHome("2db6", filmRunning, extras, nothingOnPhone))

        val filmDone = listOf(
            job("torrent:2db6:1", HomeJobState.DONE),
            job("torrent:2db6:2", HomeJobState.DONE),
        ).associateBy { MediaItemId(it.id) }
        assertEquals(
            TorrentAtHome.Ready(listOf(MediaItemId("torrent:2db6:1"))),
            torrentAtHome("2db6", filmDone, extras, nothingOnPhone),
        )
    }

    @Test
    fun `a film the Pi is retrying is still downloading, not gone`() {
        val retrying = listOf(
            job("torrent:2db6:1", HomeJobState.WAITING).copy(retry = HomeRetry(2, 4, 1)),
        ).associateBy { MediaItemId(it.id) }
        assertEquals(TorrentAtHome.Downloading(0), torrentAtHome("2db6", retrying, nothingOnPhone))
    }

    @Test
    fun `another torrent or no hash says nothing`() {
        val jobs = listOf(job("torrent:ffff:1", HomeJobState.DONE)).associateBy { MediaItemId(it.id) }
        assertNull(torrentAtHome("2db6", jobs, nothingOnPhone))
        assertNull(torrentAtHome(null, jobs, nothingOnPhone))
    }
}
