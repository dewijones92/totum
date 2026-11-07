package com.dewijones92.totum.ui.playlist

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.playlist.fake.InMemoryLocalPlaylistStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlaylistDetailUndoTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun video(id: String) = PlayableItem(
        item = MediaItem(MediaItemId(id), SourceId("ch"), id, publishedAt = null, duration = null),
        handle = PlayHandle.Video(HttpUrl.of("https://youtube.com/watch?v=$id")),
    )

    private class Rig(val store: InMemoryLocalPlaylistStore, val id: PlaylistId, val vm: LocalPlaylistDetailViewModel)

    private suspend fun TestScope.rig(vararg items: String): Rig {
        val store = InMemoryLocalPlaylistStore()
        val id = store.create("Road trip")
        items.forEach { store.addItem(id, video(it)) }
        val controller = FakePlaybackController()
        val queue = PlaybackQueue(
            controller,
            VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            CoroutineScope(dispatcher),
            InMemoryQueueStore(),
        )
        val vm = LocalPlaylistDetailViewModel(
            id = id,
            store = store,
            queue = queue,
            downloads = FakeDownloadManager(),
            playlistRemovals = PlaylistRemovals(store, backgroundScope),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.items.collect { } }
        advanceUntilIdle()
        return Rig(store, id, vm)
    }

    private suspend fun Rig.stored() = store.observeItems(id).first().map { it.item.id.value }

    private fun TestScope.removals(vm: LocalPlaylistDetailViewModel): List<List<LocalPlaylistDetailViewModel.Removed>> {
        val heard = mutableListOf<List<LocalPlaylistDetailViewModel.Removed>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.removed.collect { heard += it } }
        return heard
    }

    @Test
    fun `removing several items offers them all with their old places and undo puts each back in its slot`() =
        runTest(dispatcher) {
            val rig = rig("a", "b", "c", "d", "e")
            val heard = removals(rig.vm)

            rig.vm.removeAll(listOf(MediaItemId("d"), MediaItemId("b")))
            advanceUntilIdle()

            assertEquals(listOf("a", "c", "e"), rig.stored())
            val offered = heard.single()
            assertEquals(listOf(1 to "b", 3 to "d"), offered.map { it.index to it.item.item.id.value })

            rig.vm.restore(offered)
            advanceUntilIdle()

            assertEquals(listOf("a", "b", "c", "d", "e"), rig.stored())
        }

    @Test
    fun `removing an item that is not in the playlist offers no undo`() = runTest(dispatcher) {
        val rig = rig("a", "b")
        val heard = removals(rig.vm)

        rig.vm.removeAll(listOf(MediaItemId("zzz")))
        advanceUntilIdle()

        assertTrue(heard.toString(), heard.isEmpty())
        assertEquals(listOf("a", "b"), rig.stored())
    }

    @Test
    fun `a single remove is the same thing and undo restores the first slot`() = runTest(dispatcher) {
        val rig = rig("a", "b", "c")
        val heard = removals(rig.vm)

        rig.vm.remove(MediaItemId("a"))
        advanceUntilIdle()
        assertEquals(listOf("b", "c"), rig.stored())

        rig.vm.restore(heard.single())
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), rig.stored())
    }
}
