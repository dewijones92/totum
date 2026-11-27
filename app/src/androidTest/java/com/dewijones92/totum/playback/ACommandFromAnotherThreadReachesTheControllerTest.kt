package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.domain.ChosenWay
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.PlaybackWaits
import com.dewijones92.totum.support.SilentWav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.concurrent.thread

class ACommandFromAnotherThreadReachesTheControllerTest {

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
        app.container.playbackQueue.clear()
        controller.player?.stop()
        controller.player?.clearMediaItems()
    }

    @After
    fun tidy(): Unit = runBlocking<Unit>(Dispatchers.Main) {
        controller.player?.stop()
        controller.player?.clearMediaItems()
    }

    @Test
    fun aPlayReasonSetOffTheMainThreadIsNotACrashAndIsStillRecorded(): Unit = runBlocking<Unit>(Dispatchers.Main) {
        val file = File(context.cacheDir, "$ID.wav").apply { writeBytes(SilentWav.bytes(SECONDS)) }
        val media = MediaItem(
            id = MediaItemId(ID),
            sourceId = SourceId("test"),
            title = ID,
            publishedAt = null,
            duration = null,
            mediaUrl = null,
        )
        controller.play(media, MediaKind.PODCAST, localPath = file.absolutePath)
        assertNotNull(
            "the item never started playing",
            PlaybackWaits.awaitStateOf(controller, media.id, TIMEOUT_MS) { it.isPlaying },
        )

        var failure: Throwable? = null
        val worker = thread(name = "not-the-main-thread") {
            try {
                controller.setPlayReason(media.id, REASON)
                controller.showSwitchButton(null)
            } catch (error: Throwable) {
                failure = error
            }
        }
        withContext(Dispatchers.IO) { worker.join(TIMEOUT_MS) }

        assertNull(
            "a command given to the controller from another thread must not throw: MediaController " +
                "belongs to the main thread, and the queue is called from wherever its caller happens to run",
            failure,
        )
        val recorded = withTimeoutOrNull(TIMEOUT_MS) {
            while (controller.state.value?.playReason != REASON) delay(POLL_MS)
            true
        }
        assertEquals("the reason given from the other thread was never recorded", true, recorded)
    }

    private companion object {
        const val ID = "command-from-a-worker"
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
        const val SECONDS = 20
        val REASON = PlayReason.Chosen(ChosenWay.PIN)
    }
}
