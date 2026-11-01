package com.dewijones92.totum.ui.common

import com.dewijones92.totum.R
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowTest {
    private val item = BackupItem(
        "torrent:abc:1",
        "torrent",
        "Toy Story 5",
        contentKind = "STANDARD",
        playbackType = "VIDEO"
    )
    private fun job(state: HomeJobState) = HomeJob("torrent:abc:1", item, state, audioOnly = false)

    @Test
    fun `a film kept at home says it stays there, not that it copies on Wi-Fi`() {
        assertEquals(R.string.home_state_done_kept, HomeRow.doneWords(onPhone = false, copiesItself = false))
        assertEquals(R.string.home_state_done, HomeRow.doneWords(onPhone = false, copiesItself = true))
        assertEquals(R.string.home_state_done_on_phone, HomeRow.doneWords(onPhone = true, copiesItself = false))
    }

    @Test
    fun `a finished row not on the phone offers a copy to the phone`() {
        val none = DownloadState.NotDownloaded
        assertTrue(HomeRow.offers(job(HomeJobState.DONE), none).copyNow)
        assertFalse(HomeRow.offers(job(HomeJobState.DONE), DownloadState.Downloaded("/x.mkv")).copyNow)
        assertFalse(HomeRow.offers(job(HomeJobState.DOWNLOADING), none).copyNow)
        assertTrue(HomeRow.offers(job(HomeJobState.FAILED), none).retry)
    }

    @Test
    fun `no copy is offered while the copy is already running`() {
        assertFalse(HomeRow.offers(job(HomeJobState.DONE), DownloadState.Downloading(100, 1_000)).copyNow)
        assertTrue(HomeRow.offers(job(HomeJobState.DONE), DownloadState.Downloaded("/x.m4a", audioOnly = true)).copyNow)
    }
}
