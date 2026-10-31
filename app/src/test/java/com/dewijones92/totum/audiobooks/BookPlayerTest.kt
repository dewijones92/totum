package com.dewijones92.totum.audiobooks

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.podcast.PreviewResult
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookPlayerTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val book = MediaSource.PodcastFeed(
        id = SourceId("podcast:book"),
        title = "The War of the Worlds",
        feedUrl = HttpUrl.of("https://archive.org/metadata/war_of_the_worlds_librivox"),
    )
    private val chapters = (1..4).map { n ->
        MediaItem(
            id = MediaItemId("chapter-$n"),
            sourceId = book.id,
            title = "Chapter $n",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://archive.org/download/x/$n.mp3"),
            contentKind = MediaContentKind.AUDIOBOOK,
        )
    }

    private fun queue() = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        offline = { false },
        localCopy = { null },
    )

    private fun player(q: PlaybackQueue, states: Map<MediaItemId, PlayState>) = BookPlayer(
        podcasts = FakePodcastRepository().also { it.previews[book.feedUrl] = PreviewResult.Loaded(book, chapters) },
        queue = q,
        states = { states },
    )

    @Test
    fun `a book queues as one group in chapter order and carries on where you were`() = runTest(dispatcher) {
        val q = queue()
        val states = mapOf(
            chapters[0].id to PlayState.Played,
            chapters[1].id to PlayState.InProgress(positionMs = 60_000, durationMs = 600_000),
        )

        val played = player(q, states).play(book, "🎧 Opening the book")
        advanceUntilIdle()

        assertTrue(played)
        assertEquals("chapter-2", q.nowPlaying.value?.item?.id?.value)
        assertEquals(
            listOf("chapter-1", "chapter-2", "chapter-3", "chapter-4"),
            q.state.value.entries.map { it.item.item.id.value },
        )
        assertEquals(setOf(BookPlayer.groupId(book)), q.state.value.entries.map { it.group?.id }.toSet())
    }

    @Test
    fun `a chapter tapped on the book's page starts there, still as the whole book`() = runTest(dispatcher) {
        val q = queue()

        player(q, emptyMap()).play(book, "🎧", startAt = chapters[2].id)
        advanceUntilIdle()

        assertEquals("chapter-3", q.nowPlaying.value?.item?.id?.value)
        assertEquals(4, q.state.value.entries.size)
    }

    @Test
    fun `a book that will not load plays nothing`() = runTest(dispatcher) {
        val q = queue()
        val nowhere = BookPlayer(FakePodcastRepository(), q, { emptyMap() })

        val played = nowhere.play(book, "🎧")
        advanceUntilIdle()

        assertEquals(false, played)
        assertEquals(0, q.state.value.entries.size)
    }
}
