package com.dewijones92.totum.ui.common

import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.data.source.DefaultSourceLocator
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.settings.PlaybackMode
import com.dewijones92.totum.settings.listeningIn
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A row's label has to match what the app is actually doing.
 *
 * `audioMode` was `playbackMode == AUDIO`, which is false on [PlaybackMode.AUTO] however metered the
 * connection is — and AUTO is the shipped default, meaning "video on Wi-Fi, audio on mobile data". So
 * on 4G the app played audio while every row's sheet offered "Listen only" with a headphones glyph and
 * never offered "Watch with video": the label saying the opposite of what was happening, with no
 * one-tap route back to the picture.
 *
 * Playback had the right answer all along — `AppContainer.audioPlaybackPreferred()` — and the rows
 * simply were not asking it. This asserts they now do.
 */
class ARowLabelsWhatIsActuallyHappeningTest {

    /** THE case: default mode, mobile data. */
    @Test
    fun `on a metered network with the default mode a row says we are listening`() {
        val actions = actionsWhere(listening = listeningIn(PlaybackMode.AUTO, onMeteredNetwork = true))

        assertTrue(
            "the row read \"not listening\" while playback was already playing audio, so it offered " +
                "\"Listen only\" and hid the way back to the picture",
            actions.audioMode,
        )
    }

    @Test
    fun `on wifi with the default mode a row says we are watching`() {
        val actions = actionsWhere(listening = listeningIn(PlaybackMode.AUTO, onMeteredNetwork = false))

        assertFalse("AUTO on Wi-Fi is watching", actions.audioMode)
    }

    /** An explicit choice must win over the network, in both directions. */
    @Test
    fun `an explicit mode ignores the network`() {
        assertTrue(actionsWhere(listeningIn(PlaybackMode.AUDIO, onMeteredNetwork = false)).audioMode)
        assertFalse(actionsWhere(listeningIn(PlaybackMode.VIDEO, onMeteredNetwork = true)).audioMode)
    }

    private fun actionsWhere(listening: Boolean): MediaItemActions {
        val controller = FakePlaybackController()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return MediaItemActions(
            queue = PlaybackQueue(
                controller = controller,
                launcher = VideoPlaybackLauncher(
                    VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                    controller,
                    FakeYouTubeWatchHistory(),
                    InMemoryPlayHistoryStore(),
                ),
                scope = scope,
            ),
            openPlaylistPicker = {},
            locator = DefaultSourceLocator(FakePodcastRepository(), FakeYtDlpEngine()),
            scope = scope,
            mode = object : ListenMode {
                override val listening = listening
                override fun choose(audio: Boolean) = Unit
            },
            ui = object : UiEffects {
                override fun announce(message: String) = Unit
                override fun expandPlayer() = Unit
            },
        )
    }
}
