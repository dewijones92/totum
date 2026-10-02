package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.PlaybackWaits
import com.dewijones92.totum.support.SilentWav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class AHeldPauseStartsPausedTest {

    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val controller get() = app.container.playbackController

    @Before
    fun ready(): Unit = runBlocking<Unit>(Dispatchers.Main) {
        val connected = withTimeoutOrNull(TIMEOUT_MS) {
            while (controller.player == null) delay(POLL_MS)
            true
        }
        assertEquals("the media controller never connected", true, connected)
        controller.setSkipSilence(false)
        app.container.playbackQueue.clear()
        controller.player?.stop()
        controller.player?.clearMediaItems()
    }

    @After
    fun tidy(): Unit = runBlocking<Unit>(Dispatchers.Main) {
        controller.holdPausedForNextPlay(null)
        controller.player?.stop()
        controller.player?.clearMediaItems()
    }

    private fun item(id: String): Pair<MediaItem, String> {
        val file = File(context.cacheDir, "$id.wav").apply { writeBytes(SilentWav.bytes(SECONDS)) }
        val media = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("test"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = null
        )
        return media to file.absolutePath
    }

    @Test
    fun aHeldItemIsPreparedButNotStartedByTheRealPlayer(): Unit = runBlocking<Unit>(Dispatchers.Main) {
        val (media, path) = item("held")
        controller.holdPausedForNextPlay(media.id)

        controller.play(media, MediaKind.PODCAST, localPath = path)
        controller.holdPausedForNextPlay(null)

        val loaded = PlaybackWaits.awaitStateOf(
            controller,
            media.id,
            TIMEOUT_MS
        ) { it.durationMs != null && !it.isBuffering }
        assertNotNull("the held item never loaded", loaded)
        delay(SETTLE_MS)
        val state = controller.state.value!!
        assertFalse("a held item must not start playing", state.wantsToPlay)
        assertFalse(state.isPlaying)
    }

    @Test
    fun withoutAHoldTheSameItemPlays(): Unit = runBlocking<Unit>(Dispatchers.Main) {
        val (media, path) = item("unheld")

        controller.play(media, MediaKind.PODCAST, localPath = path)

        assertNotNull(
            "an unheld item must play",
            PlaybackWaits.awaitStateOf(controller, media.id, TIMEOUT_MS) { it.isPlaying }
        )
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
        const val SETTLE_MS = 1_500L
        const val SECONDS = 20
    }
}
