package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.home.JellyfinSeriesRun
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.ChosenWay
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayReason
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
class AJellyfinEpisodeCarriesOnThroughItsSeriesTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val series = QueueGroup("jellyfin:series:series00", "📺 Bluey")
    private val asked = mutableListOf<String>()
    private var unreadable = false
    private var offline = false

    private fun episode(season: Int, number: Int) = PlayableItem(
        MediaItem(
            id = MediaItemId("jellyfin:ep0$season$number"),
            sourceId = SourceId("jellyfin:Bluey"),
            title = "S0${season}E0$number · Episode $number",
            publishedAt = null,
            duration = null,
            author = "Bluey",
            mediaUrl = HttpUrl.of("https://pi.test/jellyfin/stream/ep0$season$number.mkv"),
        ),
        PlayHandle.Podcast(),
    )

    private val wholeSeries = listOf(episode(1, 1), episode(1, 2), episode(1, 3), episode(2, 1), episode(2, 2))

    private fun film() = PlayableItem(
        MediaItem(
            id = MediaItemId("jellyfin:film0000"),
            sourceId = SourceId("jellyfin:films"),
            title = "Heat",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://pi.test/jellyfin/stream/film0000.mkv"),
        ),
        PlayHandle.Podcast(),
    )

    private val queue = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        offline = { offline },
        seriesRun = { item ->
            asked += item.id.value
            JellyfinSeriesRun(series, wholeSeries).takeUnless { unreadable }
        },
    )

    private fun ids() = queue.state.value.entries.map { it.item.item.id.value }

    private fun playingId() = queue.state.value.current?.item?.item?.id?.value

    @Test
    fun `an episode played from anywhere queues as its series and starts at that episode`() = runTest(dispatcher) {
        queue.playNow(episode(1, 2))
        advanceUntilIdle()

        assertEquals(wholeSeries.map { it.item.id.value }, ids())
        assertEquals("jellyfin:ep012", playingId())
        assertEquals(setOf(series.id), queue.state.value.entries.mapNotNull { it.group?.id }.toSet())
    }

    @Test
    fun `it carries on through the rest of its season and then the next season`() = runTest(dispatcher) {
        queue.playNow(episode(1, 3))
        advanceUntilIdle()

        queue.playNextInQueue()
        advanceUntilIdle()

        assertEquals("jellyfin:ep021", playingId())
    }

    @Test
    fun `a series that cannot be read leaves the episode playing on its own`() = runTest(dispatcher) {
        unreadable = true

        queue.playNow(episode(1, 2))
        advanceUntilIdle()

        assertEquals(listOf("jellyfin:ep012"), ids())
        assertEquals("jellyfin:ep012", playingId())
    }

    @Test
    fun `a film is never looked up as part of a series`() = runTest(dispatcher) {
        queue.playNow(film())
        advanceUntilIdle()

        assertEquals(emptyList<String>(), asked)
        assertEquals(listOf("jellyfin:film0000"), ids())
    }

    @Test
    fun `an episode already in a group plays there and the series is not read again`() = runTest(dispatcher) {
        queue.playAll(wholeSeries, series)
        advanceUntilIdle()
        asked.clear()

        queue.playNow(episode(2, 1))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), asked)
        assertEquals(wholeSeries.map { it.item.id.value }.toSet(), ids().toSet())
        assertEquals(setOf(series.id), queue.state.value.entries.mapNotNull { it.group?.id }.toSet())
        assertEquals("jellyfin:ep021", playingId())
    }

    @Test
    fun `going back or forward through the trail plays the one episode and nothing more`() = runTest(dispatcher) {
        queue.playNow(episode(1, 2), reason = PlayReason.Chosen(ChosenWay.BACK_OR_FORWARD))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), asked)
        assertEquals(listOf("jellyfin:ep012"), ids())
    }

    @Test
    fun `the app's own moves are not turned into a series`() = runTest(dispatcher) {
        queue.playNow(episode(1, 2), byYou = false)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), asked)
        assertEquals(listOf("jellyfin:ep012"), ids())
    }

    @Test
    fun `with no network the series is not read and the episode plays on its own`() = runTest(dispatcher) {
        offline = true

        queue.playNow(episode(1, 2))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), asked)
        assertEquals(listOf("jellyfin:ep012"), ids())
    }
}
