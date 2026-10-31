package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.PlaybackWaits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

class SkipSilenceSurvivesARestartTest {
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class SavedChoices(var skipSilence: Boolean) : PlaybackSpeedStore {
        override suspend fun speed(): Float = DEFAULT_PLAYBACK_SPEED
        override suspend fun save(speed: Float) = Unit
        override suspend fun skipSilence(): Boolean = skipSilence
        override suspend fun saveSkipSilence(on: Boolean) {
            skipSilence = on
        }
    }

    @Test
    fun aFreshControllerUsesTheSavedChoiceAndSavesAChange() = runBlocking(Dispatchers.Main) {
        val clip = File(context.cacheDir, "skip-silence-clip.mp4")
        instrumentation.context.assets.open("clip.mp4").use { input -> clip.outputStream().use(input::copyTo) }
        val saved = SavedChoices(skipSilence = true)
        val work = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val fresh = Media3PlaybackController(context, work, speedStore = saved)
        try {
            withTimeout(TIMEOUT) { while (fresh.player == null) delay(100) }
            fresh.play(talk(), localPath = clip.absolutePath)
            val playing = PlaybackWaits.awaitStateOf(fresh, ID, TIMEOUT) { it.isPlaying }
            assertTrue("The player must start: ${fresh.state.value}", playing != null)
            assertEquals("the saved choice after a restart", true, fresh.state.value?.skipSilence)

            fresh.setSkipSilence(false)
            withTimeout(TIMEOUT) { while (saved.skipSilence) delay(50) }
            assertEquals(false, saved.skipSilence)
        } finally {
            fresh.player?.stop()
            fresh.player?.clearMediaItems()
            fresh.player?.release()
            work.cancel()
            clip.delete()
        }
    }

    private fun talk() = MediaItem(
        ID,
        SourceId("test:skip-silence"),
        "A talk",
        publishedAt = null,
        duration = 90.seconds
    )

    private companion object {
        val ID = MediaItemId("test-skip-silence-restart")
        const val TIMEOUT = 15000L
    }
}
