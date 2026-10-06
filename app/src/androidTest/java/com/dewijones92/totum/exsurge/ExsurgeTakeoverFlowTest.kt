package com.dewijones92.totum.exsurge

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.SilentWav
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExsurgeTakeoverFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val exsurge = (context as TotumApplication).container.exsurge
    private lateinit var before: ExsurgeSettings

    @Before
    fun summon() {
        before = exsurge.view.value.settings
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            exsurge.updateSettings("test") {
                it.copy(
                    enabled = true,
                    quietOffice = true,
                    takeoverOverApps = false,
                    destinationPackage = context.packageName,
                    destinationRoute = ""
                )
            }
            exsurge.dispatch(ExsurgeEvent.SummonNow, "test")
        }
    }

    @After
    fun restore() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            exsurge.updateSettings("test") { before.copy(enabled = false) }
            exsurge.updateSettings("test") { before }
        }
    }

    @Test
    fun goFromTheTakeoverStartsTheBreak() {
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.Summoned)
        ActivityScenario.launch<TakeoverActivity>(TakeoverActivity.intent(context)).use {
            compose.onNodeWithTag("exsurge-go").performScrollTo().performClick()
            compose.waitUntil(5_000) { exsurge.view.value.memory.state !is ExsurgeState.Summoned }
        }
        val state = exsurge.view.value.memory.state
        assertTrue(
            "expected the break to have started, was ${state.label()}",
            state is ExsurgeState.Rising || state is ExsurgeState.OnBreak
        )
    }

    @Test
    fun aChosenBreakLengthIsRememberedAndFixedOnTheBreak() {
        ActivityScenario.launch<TakeoverActivity>(TakeoverActivity.intent(context)).use {
            compose.onNodeWithTag("exsurge-break-10").performScrollTo().performClick()
            compose.waitUntil(5_000) { exsurge.view.value.settings.breakMinutes == 10 }
            compose.onNodeWithTag("exsurge-walk").performScrollTo().performClick()
            compose.waitUntil(5_000) { exsurge.view.value.memory.state !is ExsurgeState.Summoned }
        }
        val state = exsurge.view.value.memory.state
        val summons = (state as? ExsurgeState.Rising)?.summons ?: (state as? ExsurgeState.OnBreak)?.summons
        assertEquals("break length on ${state.label()}", 10, summons?.breakMinutes)
        assertEquals(10, exsurge.view.value.settings.breakMinutes)
    }

    @Test
    fun justWalkFromTheTakeoverStartsTheBreakWithoutTheLanguageApp() {
        ActivityScenario.launch<TakeoverActivity>(TakeoverActivity.intent(context)).use {
            compose.onNodeWithTag("exsurge-walk").performScrollTo().performClick()
            compose.waitUntil(5_000) { exsurge.view.value.memory.state !is ExsurgeState.Summoned }
        }
        val state = exsurge.view.value.memory.state
        val summons = (state as? ExsurgeState.Rising)?.summons ?: (state as? ExsurgeState.OnBreak)?.summons
        assertTrue("expected a walk-only break, was ${state.label()}", summons?.practise == false)
    }

    @Test
    fun continueTotumFromTheTakeoverStartsAWalkingBreak() {
        ActivityScenario.launch<TakeoverActivity>(TakeoverActivity.intent(context)).use {
            compose.onNodeWithTag("exsurge-continue").performScrollTo().performClick()
            compose.waitUntil(5_000) { exsurge.view.value.memory.state !is ExsurgeState.Summoned }
        }
        val state = exsurge.view.value.memory.state
        val summons = (state as? ExsurgeState.Rising)?.summons ?: (state as? ExsurgeState.OnBreak)?.summons
        assertTrue("expected a walking break, was ${state.label()}", summons?.practise == false)
    }

    @Test
    fun continueTotumResumesThePausedPodcastWithoutReplayingIt() = continueCurrent(MediaKind.PODCAST, paused = true)

    @Test
    fun continueTotumKeepsTheVideoPlayingDuringTheWalkingBreak() = continueCurrent(MediaKind.VIDEO, paused = false)

    private fun continueCurrent(kind: MediaKind, paused: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val playback = (context as TotumApplication).container.playbackController
        ActivityScenario.launch<MainActivity>(android.content.Intent(context, MainActivity::class.java)).use {
            compose.waitUntil(10_000) { playback.player != null }
            val file = fixture(kind)
            val item = MediaItem(
                MediaItemId("exsurge-continue-${kind.name}"),
                SourceId("test"),
                "Continue Totum test",
                publishedAt = null,
                duration = null
            )
            val beforeSkipSilence = playback.state.value?.skipSilence ?: false
            try {
                instrumentation.runOnMainSync {
                    playback.setSkipSilence(false)
                    playback.play(item, kind = kind, localPath = file.absolutePath, startPositionMs = 10_000)
                }
                compose.waitUntil(10_000) { playback.state.value?.let { it.itemId == item.id && it.isPlaying } == true }
                if (paused) {
                    instrumentation.runOnMainSync { playback.setPlaying(false) }
                    compose.waitUntil(5_000) { playback.state.value?.wantsToPlay == false }
                }
                val position = playback.state.value!!.positionMs
                ActivityScenario.launch<TakeoverActivity>(TakeoverActivity.intent(context)).use {
                    compose.onNodeWithTag("exsurge-continue").performScrollTo()
                    capture()
                    compose.onNodeWithTag("exsurge-continue").performClick()
                    compose.waitUntil(5_000) {
                        exsurge.view.value.memory.state !is ExsurgeState.Summoned &&
                            playback.state.value?.let { it.itemId == item.id && it.isPlaying } == true
                    }
                }
                assertTrue("the current item was restarted", playback.state.value!!.positionMs >= position)
                assertEquals(item.id, playback.state.value!!.itemId)
                instrumentation.runOnMainSync { playback.setPlaying(false) }
                compose.waitUntil(5_000) { playback.state.value?.wantsToPlay == false }
                instrumentation.runOnMainSync { exsurge.dispatch(ExsurgeEvent.TurnOff, "test") }
                compose.waitForIdle()
                assertEquals(false, playback.state.value!!.wantsToPlay)
            } finally {
                instrumentation.runOnMainSync {
                    playback.setPlaying(false)
                    playback.setSkipSilence(beforeSkipSilence)
                    playback.player?.stop()
                    playback.player?.clearMediaItems()
                }
                file.delete()
            }
        }
    }

    private fun fixture(kind: MediaKind): File {
        val file = File(context.cacheDir, "exsurge-continue-${kind.name}")
        if (kind == MediaKind.PODCAST) {
            file.writeBytes(SilentWav.bytes(90))
        } else {
            InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4").use { input ->
                file.outputStream().use(input::copyTo)
            }
        }
        return file
    }

    private fun capture() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val width = context.resources.displayMetrics.widthPixels
        val file = File(context.cacheDir, "exsurge-continue-$width.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test
    fun skipFromTheTakeoverRecordsItAndRestartsTheClock() {
        ActivityScenario.launch<TakeoverActivity>(TakeoverActivity.intent(context)).use {
            compose.onNodeWithTag("exsurge-skip").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                val state = exsurge.view.value.memory.state
                state is ExsurgeState.Sitting || state is ExsurgeState.Dormant
            }
        }
        assertTrue(exsurge.view.value.stats.today.skipped >= 1)
    }
}
