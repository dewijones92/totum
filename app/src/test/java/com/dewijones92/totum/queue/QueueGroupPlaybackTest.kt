package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsKeepPlayedItemsTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val album = QueueGroup("album:a", "An album")
    private val queue = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
    )

    private fun track(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("album"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private fun inAlbum() = queue.state.value.entries.filter { it.group?.id == album.id }.map { it.item.item.id.value }

    @Test
    fun `playing through a group keeps every song in it`() = runTest(dispatcher) {
        queue.playAll(listOf("t1", "t2", "t3").map(::track), album)
        advanceUntilIdle()

        queue.playNextInQueue()
        queue.playNextInQueue()
        advanceUntilIdle()

        assertEquals(listOf("t1", "t2", "t3"), inAlbum())
        assertEquals("t3", queue.state.value.current?.item?.item?.id?.value)
    }

    @Test
    fun `tapping a song that is already in a group plays it and leaves it in the group`() = runTest(dispatcher) {
        queue.playAll(listOf("t1", "t2", "t3").map(::track), album)
        advanceUntilIdle()

        queue.playNow(track("t3"))
        advanceUntilIdle()

        assertEquals(setOf("t1", "t2", "t3"), inAlbum().toSet())
        assertEquals("t3", queue.state.value.current?.item?.item?.id?.value)
    }
}
