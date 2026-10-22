package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
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
        val asked = mutableListOf<LockRefusal>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.refusals.collect { asked += it } }

        manager.delete(rachel.id)

        assertTrue(manager.observe(rachel.id).first() is DownloadState.Downloaded)
        assertEquals(listOf<LockRefusal>(LockRefusal.Delete(rachel.id)), asked)

        manager.unlockAndDelete(rachel.id)

        assertEquals(DownloadState.NotDownloaded, manager.observe(rachel.id).first())
        assertEquals(emptySet<MediaItemId>(), manager.locks.locked.value)
    }

    @Test
    fun `a locked copy is not replaced by a new download and a failed row is not guarded`() = runTest {
        val inner = FakeDownloadManager()
        val manager = LockingDownloadManager(inner, InMemoryDownloadLocks())
        manager.download(rachel, audioOnly = true)
        manager.setLocked(rachel.id, true)
        val asked = mutableListOf<LockRefusal>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.refusals.collect { asked += it } }

        manager.download(rachel.asPlayable(), audioOnly = false)
        manager.download(rachel, audioOnly = false)

        assertEquals(
            DownloadState.Downloaded("/fake/rachel.media", audioOnly = true),
            manager.observe(rachel.id).first()
        )
        assertEquals(2, asked.count { it is LockRefusal.Replace })

        val failed = rachel.copy(id = MediaItemId("failed"))
        manager.setLocked(failed.id, true)
        inner.setFailed(failed.id, "timeout")
        manager.delete(failed.id)
        assertEquals(2, asked.size)
    }
}
