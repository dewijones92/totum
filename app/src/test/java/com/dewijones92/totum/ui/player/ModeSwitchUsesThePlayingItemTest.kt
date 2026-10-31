package com.dewijones92.totum.ui.player

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.data.source.DefaultSourceLocator
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.LocalCopy
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.actions.fake.FakeYouTubeActions
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import com.dewijones92.totum.innertube.comments.fake.FakeYouTubeComments
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.related.fake.FakeYouTubeRelated
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.settings.InMemoryAppPreferences
import com.dewijones92.totum.settings.PlaybackMode
import com.dewijones92.totum.ui.common.ListenMode
import com.dewijones92.totum.ui.common.MediaItemActions
import com.dewijones92.totum.ui.common.UiEffects
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModeSwitchUsesThePlayingItemTest {
    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val preferences = InMemoryAppPreferences()
    private val engine = FakeYtDlpEngine()
    private val copies = mutableMapOf<MediaItemId, LocalCopy>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a full local podcast file retains its picture except in Listen mode`() = runTest(dispatcher) {
        val (queue, _) = fixture(backgroundScope)
        val local = video("current").copy(handle = PlayHandle.Podcast("/downloads/episode.mp4"))
        preferences.setPlaybackMode(PlaybackMode.VIDEO)

        queue.peek(local)

        assertEquals(false, controller.lastAudioOnly)
        assertEquals("/downloads/episode.mp4", controller.lastLocalPath)

        preferences.setPlaybackMode(PlaybackMode.AUDIO)
        queue.peek(local)

        assertEquals(true, controller.lastAudioOnly)
        assertEquals("/downloads/episode.mp4", controller.lastLocalPath)
    }

    @Test
    fun `the row mode switch keeps the current full download at its exact position`() = runTest(dispatcher) {
        val (queue, _) = fixture(backgroundScope)
        copies[MediaItemId("current")] = LocalCopy("/downloads/current.mkv")
        queue.peek(video("current"))
        controller.seekTo(POSITION)
        val announced = mutableListOf<String>()
        val rows = MediaItemActions(
            queue,
            {},
            DefaultSourceLocator(FakePodcastRepository(), engine),
            this,
            object : ListenMode {
                override val listening get() = preferences.settings.value.playbackMode == PlaybackMode.AUDIO
                override fun choose(audio: Boolean) = preferences.setPlaybackMode(
                    if (audio) PlaybackMode.AUDIO else PlaybackMode.VIDEO,
                )
            },
            object : UiEffects {
                override fun announce(message: String) {
                    announced += message
                }
                override fun expandPlayer() = Unit
                override fun modeMessage(audio: Boolean) = if (audio) "🎧 Audio mode on" else "📺 Video mode on"
            },
        )

        rows.switchMode(video("current").item, toAudio = true, audioOnMessage = "", videoOnMessage = "")
        advanceUntilIdle()

        assertEquals(listOf("🎧 Audio mode on"), announced)

        assertEquals(POSITION, controller.lastStartPositionMs)
        assertEquals("/downloads/current.mkv", controller.lastLocalPath)
        assertEquals(-1, queue.state.value.currentIndex)
    }

    @Test
    fun `watch after a different downloaded audio item plays that item at its current position`() = runTest(
        dispatcher
    ) {
        val (queue, model) = fixture(backgroundScope)
        queue.peek(video("earlier"))
        copies[MediaItemId("current")] = LocalCopy("/downloads/current.m4a", audioOnly = true)
        preferences.setPlaybackMode(PlaybackMode.AUDIO)
        queue.peek(video("current"))
        controller.seekTo(POSITION)

        model.watch()
        advanceUntilIdle()

        assertEquals(MediaItemId("current"), controller.lastItem?.id)
        assertEquals(POSITION, controller.lastStartPositionMs)
        assertEquals("https://media.test/current-video?n=solved", controller.lastItem?.mediaUrl?.value)
    }

    @Test
    fun `watching a downloaded full video resumes at the exact position`() = runTest(dispatcher) {
        val (queue, model) = fixture(backgroundScope)
        copies[MediaItemId("current")] = LocalCopy("/downloads/current.mkv")
        preferences.setPlaybackMode(PlaybackMode.AUDIO)
        queue.peek(video("current"))
        controller.seekTo(POSITION)

        model.watch()
        advanceUntilIdle()

        assertEquals(POSITION, controller.lastStartPositionMs)
        assertEquals("/downloads/current.mkv", controller.lastLocalPath)
    }

    @Test
    fun `listening after a different downloaded audio item never plays the earlier stream`() = runTest(dispatcher) {
        val (queue, model) = fixture(backgroundScope)
        queue.peek(video("earlier"))
        copies[MediaItemId("current")] = LocalCopy("/downloads/current.m4a", audioOnly = true)
        preferences.setPlaybackMode(PlaybackMode.AUDIO)
        queue.peek(video("current"))
        controller.seekTo(POSITION)

        model.listen()
        advanceUntilIdle()

        assertEquals(MediaItemId("current"), controller.lastItem?.id)
        assertEquals("/downloads/current.m4a", controller.lastLocalPath)
        assertEquals(POSITION, controller.lastStartPositionMs)
    }

    @Test
    fun `watch this one overrides Listen without changing the saved mode`() = runTest(dispatcher) {
        val (queue, model) = fixture(backgroundScope)
        copies[MediaItemId("current")] = LocalCopy("/downloads/current.m4a", audioOnly = true)
        preferences.setPlaybackMode(PlaybackMode.AUDIO)
        queue.peek(video("current"))
        controller.seekTo(POSITION)

        model.watchOnce()
        advanceUntilIdle()

        assertEquals(MediaItemId("current"), controller.lastItem?.id)
        assertEquals("https://media.test/current-video?n=solved", controller.lastItem?.mediaUrl?.value)
        assertEquals(POSITION, controller.lastStartPositionMs)
        assertEquals(PlaybackMode.AUDIO, preferences.settings.value.playbackMode)
    }

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Pair<PlaybackQueue, WatchViewModel> {
        for (id in listOf("earlier", "current")) {
            engine.registerMedia(
                watchUrl(id),
                MediaMetadata(
                    id,
                    id,
                    null,
                    900,
                    null,
                    listOf(
                        MediaFormat(
                            "137", "mp4", 1920, 1080, true, false, null,
                            "https://media.test/$id-video?n=solved", "avc1", null
                        ),
                        MediaFormat(
                            "140", "m4a", null, null, false, true, null,
                            "https://media.test/$id-audio?n=solved", null, "mp4a"
                        ),
                    ),
                ),
            )
        }
        val launcher = VideoPlaybackLauncher(
            VideoResolver(engine, SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
            audioPreferred = { preferences.settings.value.playbackMode == PlaybackMode.AUDIO },
        )
        val queue = PlaybackQueue(
            controller,
            launcher,
            scope,
            audioPreferred = { preferences.settings.value.playbackMode == PlaybackMode.AUDIO },
            localCopy = { copies[it] },
        )
        val model = WatchViewModel(
            FakeYouTubeComments(),
            FakeYouTubeRelated(),
            FakeYouTubeActions(),
            YouTubeAccount(FakeYouTubeAuth(), InMemoryTokenStore()),
            launcher,
            queue,
            preferences,
        )
        return queue to model
    }

    private fun video(id: String) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("youtube"),
            id,
            publishedAt = null,
            duration = null,
            mediaUrl = watchUrl(id)
        ),
        PlayHandle.Video(watchUrl(id)),
    )

    private fun watchUrl(id: String) = HttpUrl.of("https://www.youtube.com/watch?v=$id")

    private companion object {
        const val POSITION = 289498L
    }
}
