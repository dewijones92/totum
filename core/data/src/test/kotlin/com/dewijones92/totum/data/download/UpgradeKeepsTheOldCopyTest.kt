package com.dewijones92.totum.data.download

import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpgradeKeepsTheOldCopyTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val item = MediaItem(
        id = MediaItemId("rachel"),
        sourceId = SourceId("ms-rachel"),
        title = "Songs for Littles",
        publishedAt = null,
        duration = null,
    ).asPlayable()

    private class SoundThenHeldVideo : DownloadStrategy {
        val videoStarted = CompletableDeferred<Unit>()
        val videoEnds = CompletableDeferred<String?>()
        var videoTarget: File? = null

        override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> = flow {
            target.parentFile?.mkdirs()
            if (audioOnly) {
                target.writeText("the sound")
                emit(DownloadState.Downloaded(target.path, audioOnly = true))
                return@flow
            }
            videoTarget = target
            target.writeText("half a video")
            emit(DownloadState.Downloading(1, 2))
            videoStarted.complete(Unit)
            val failure = videoEnds.await()
            if (failure != null) {
                emit(DownloadState.Failed(failure))
            } else {
                target.writeText("the whole video")
                emit(DownloadState.Downloaded(target.path, audioOnly = false))
            }
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.soundOnDisk(strategy: SoundThenHeldVideo):
        Pair<DefaultDownloadManager, DownloadStore> {
        val store = InMemoryDownloadStore()
        val manager = DefaultDownloadManager(temp.root, store, strategy, this)
        manager.download(item, audioOnly = true)
        advanceUntilIdle()
        return manager to store
    }

    private fun DownloadState.file(): File = File((this as DownloadState.Downloaded).localPath)

    @Test
    fun `a cancelled upgrade leaves the sound copy and its record`() = runTest {
        val strategy = SoundThenHeldVideo()
        val (manager, store) = soundOnDisk(strategy)
        val sound = store.get(item.item.id)

        manager.download(item, audioOnly = false)
        strategy.videoStarted.await()
        manager.cancel(item.item.id)
        advanceUntilIdle()

        assertEquals(sound, store.get(item.item.id))
        assertEquals("the sound", sound.file().readText())
        assertFalse("the half video is gone", strategy.videoTarget!!.exists())
    }

    @Test
    fun `a failed upgrade leaves the sound copy and its record`() = runTest {
        val strategy = SoundThenHeldVideo()
        val (manager, store) = soundOnDisk(strategy)
        val sound = store.get(item.item.id)

        val told = mutableListOf<DownloadState>()
        backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler)
        ) { manager.events().collect { told += it.state } }
        manager.download(item, audioOnly = false)
        strategy.videoStarted.await()
        strategy.videoEnds.complete("timeout")
        advanceUntilIdle()

        assertEquals("the notification says it failed", DownloadState.Failed("timeout"), told.last())
        assertEquals(sound, store.get(item.item.id))
        assertEquals("the sound", sound.file().readText())
    }

    @Test
    fun `a finished upgrade replaces the sound with the video at the item's own path`() = runTest {
        val strategy = SoundThenHeldVideo()
        val (manager, store) = soundOnDisk(strategy)
        val soundPath = store.get(item.item.id).file().path

        manager.download(item, audioOnly = false)
        strategy.videoStarted.await()
        strategy.videoEnds.complete(null)
        advanceUntilIdle()

        val video = store.get(item.item.id) as DownloadState.Downloaded
        assertFalse(video.audioOnly)
        assertEquals(soundPath, video.localPath)
        assertEquals("the whole video", File(video.localPath).readText())
        assertFalse(strategy.videoTarget!!.exists() && strategy.videoTarget!!.path != soundPath)
    }
}
