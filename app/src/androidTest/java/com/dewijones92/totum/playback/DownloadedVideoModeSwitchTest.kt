package com.dewijones92.totum.playback

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.PlaybackMode
import com.dewijones92.totum.support.PlaybackWaits
import com.dewijones92.totum.ui.player.WatchViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

class DownloadedVideoModeSwitchTest {
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as TotumApplication).container
    private val controller get() = container.playbackController
    private val store = ViewModelStore()
    private lateinit var clip: File
    private lateinit var model: WatchViewModel

    @Before
    fun setUp() = runBlocking(Dispatchers.Main) {
        clip = File(context.cacheDir, "mode-switch-clip.mp4")
        instrumentation.context.assets.open("clip.mp4").use { input -> clip.outputStream().use(input::copyTo) }
        withTimeout(TIMEOUT) { while (controller.player == null) delay(100) }
        container.appPreferences.setPlaybackMode(PlaybackMode.VIDEO)
        container.playbackQueue.clear()
        controller.setSkipSilence(false)
        model = ViewModelProvider(store, WatchViewModel.factory(container))[WatchViewModel::class.java]
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.Main) {
        store.clear()
        container.playbackQueue.clear()
        controller.player?.stop()
        controller.player?.clearMediaItems()
        container.appPreferences.setPlaybackMode(PlaybackMode.AUTO)
        clip.delete()
        Unit
    }

    @Test
    fun fullLocalVideoStartsWithSoundAloneInListenMode() = runBlocking(Dispatchers.Main) {
        container.appPreferences.setPlaybackMode(PlaybackMode.AUDIO)
        container.playbackQueue.peek(item())
        awaitPlaying(video = false)
        assertEquals(ID, controller.state.value?.itemId)
        assertTrue(controller.player?.trackSelectionParameters?.disabledTrackTypes?.contains(VIDEO_TRACK) == true)
    }

    @Test
    fun playerListenAndWatchKeepTheFullLocalFileAndItsExactPosition() = runBlocking(Dispatchers.Main) {
        container.playbackQueue.peek(item())
        awaitPlaying(video = true)
        controller.seekTo(POSITION)
        withTimeout(TIMEOUT) { while ((controller.state.value?.positionMs ?: 0) < POSITION) delay(100) }

        model.listen()
        awaitPlaying(video = false)
        assertTrue(
            "Listen lost the position: ${controller.state.value}",
            (controller.state.value?.positionMs ?: 0) >= POSITION
        )
        val beforeWatch = requireNotNull(controller.state.value).positionMs

        model.watch()
        awaitPlaying(video = true)
        assertEquals(ID, controller.state.value?.itemId)
        assertTrue(
            "Watch jumped backwards: ${controller.state.value}",
            (controller.state.value?.positionMs ?: 0) >= beforeWatch
        )
    }

    @Test
    fun refreshedSponsorsArrivingBeforeResumeLookupFinishesReachTheRealPlayer() = runBlocking(Dispatchers.Main) {
        val lookupStarted = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Long?>()
        val work = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val progress = object : PlaybackProgressStore by NoOpPlaybackProgressStore {
            override suspend fun resumePositionMs(itemId: MediaItemId): Long? {
                lookupStarted.complete(Unit)
                return resume.await()
            }
        }
        val real = Media3PlaybackController(context, work, progress)
        val segments = listOf(SkipSegment(40.seconds, 50.seconds))
        try {
            withTimeout(TIMEOUT) { while (real.player == null) delay(100) }
            real.play(item().item, localPath = clip.absolutePath)
            withTimeout(TIMEOUT) { lookupStarted.await() }
            real.updateSkipSegments(ID, segments)
            resume.complete(0L)
            val started = PlaybackWaits.awaitStateOf(real, ID, TIMEOUT) { it.isPlaying }
            assertTrue("The real player must start: ${real.state.value}", started != null)
            assertEquals(segments, real.state.value?.skipSegments)
        } finally {
            real.player?.stop()
            real.player?.clearMediaItems()
            real.player?.release()
            work.cancel()
        }
    }

    private suspend fun awaitPlaying(video: Boolean) {
        val state = PlaybackWaits.awaitStateOf(controller, ID, TIMEOUT) { it.isPlaying && it.hasVideo == video }
        assertTrue("Expected video=$video on $ID, got ${controller.state.value}", state != null)
    }

    private fun item() = PlayableItem(
        MediaItem(
            ID,
            SourceId("test:mode-switch"),
            "A downloaded full video",
            publishedAt = null,
            duration = 90.seconds
        ),
        PlayHandle.LocalVideo(clip.absolutePath),
    )

    private companion object {
        val ID = MediaItemId("test-local-mode-switch")
        const val TIMEOUT = 15000L
        const val POSITION = 30000L
        const val VIDEO_TRACK = 2
    }
}
