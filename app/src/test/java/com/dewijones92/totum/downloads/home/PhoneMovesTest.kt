package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMovesTest {

    private val id = MediaItemId("torrent:abc:1")
    private val film = BackupItem(id.value, "torrent", "A film", contentKind = "STANDARD", playbackType = "VIDEO")
    private val moving = InMemoryKeptAtHome()
    private val deleted = mutableListOf<MediaItemId>()
    private var phoneBytes: Long? = 1_000
    private val moves = PhoneMoves(moving, { deleted += it }, { phoneBytes })

    private fun job(state: HomeJobState = HomeJobState.DONE, audioOnly: Boolean = false) =
        HomeJob(id.value, film, state, audioOnly, bytes = 1_000, totalBytes = 1_000)

    private fun settle(job: HomeJob?, local: DownloadState?) =
        moves.settle(listOfNotNull(job).associateBy { id }, listOfNotNull(local).associateBy { id })

    @Test
    fun `home's copy is deleted only once the phone's copy has landed with the same size`() {
        moves.begin(id)

        settle(job(), DownloadState.Downloading(200, 1_000))
        assertTrue("still copying", deleted.isEmpty())
        assertTrue(id.value in moving.ids.value)

        settle(job(), DownloadState.Downloaded("/phone/film.media"))
        assertEquals(listOf(id), deleted)
        assertTrue(moving.ids.value.isEmpty())
    }

    @Test
    fun `a phone copy of a different size leaves home's file alone`() {
        moves.begin(id)
        phoneBytes = 990

        settle(job(), DownloadState.Downloaded("/phone/film.media"))

        assertTrue(deleted.isEmpty())
        assertTrue(moving.ids.value.isEmpty())
    }

    @Test
    fun `a failed copy, or a missing file, never deletes anything at home`() {
        moves.begin(id)
        settle(job(), DownloadState.Failed("timeout"))
        assertTrue(deleted.isEmpty() && moving.ids.value.isEmpty())

        moves.begin(id)
        phoneBytes = null
        settle(job(), DownloadState.Downloaded("/phone/gone.media"))
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `a sound-only phone copy does not stand in for home's video`() {
        moves.begin(id)

        settle(job(), DownloadState.Downloaded("/phone/film.m4a", audioOnly = true))

        assertTrue(deleted.isEmpty())
        assertTrue("it waits for the video copy", id.value in moving.ids.value)
    }

    @Test
    fun `a move left from an earlier run is dropped, not carried out`() {
        moving.keep(id.value)

        settle(job(), null)

        assertTrue(deleted.isEmpty())
        assertTrue(moving.ids.value.isEmpty())
    }

    @Test
    fun `a file that left home before the copy finished drops the move`() {
        moves.begin(id)

        settle(null, DownloadState.Downloading(1, 2))

        assertTrue(deleted.isEmpty() && moving.ids.value.isEmpty())
    }
}
