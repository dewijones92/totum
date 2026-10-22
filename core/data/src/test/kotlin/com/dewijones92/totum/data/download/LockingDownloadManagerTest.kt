package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockingDownloadManagerTest {

    private val rachel = MediaItem(
        id = MediaItemId("rachel"),
        sourceId = SourceId("ms-rachel"),
        title = "Songs for Littles",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://feed.test/rachel.mp4"),
    )

    @Test
    fun `a locked download survives a delete, which asks instead, and goes once unlocked`() = runTest {
        val inner = FakeDownloadManager()
        val manager = LockingDownloadManager(inner, InMemoryDownloadLocks())
        manager.download(rachel)
        manager.setLocked(rachel.id, true)
        val asked = mutableListOf<MediaItemId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.refusals.collect { asked += it } }

        manager.delete(rachel.id)

        assertTrue(manager.observe(rachel.id).first() is DownloadState.Downloaded)
        assertEquals(listOf(rachel.id), asked)

        manager.unlockAndDelete(rachel.id)

        assertEquals(DownloadState.NotDownloaded, manager.observe(rachel.id).first())
        assertEquals(emptySet<MediaItemId>(), manager.locks.locked.value)
    }
}
