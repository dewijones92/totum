package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.SilentWav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class GaplessCrossoverTest {

    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as TotumApplication).container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue
    private val events = mutableListOf<PlaybackEvent>()
    private var listening: Job? = null
    private var gaplessBefore = true

    @Before
    fun setUp() {
        runBlocking(Dispatchers.Main) {
            withTimeoutOrNull(TIMEOUT_MS) { while (controller.player == null) delay(POLL_MS) }
            gaplessBefore = container.appPreferences.settings.value.gaplessQueue
            container.appPreferences.setAutoPlayNext(true)
            controller.setSkipSilence(false)
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
        listening = CoroutineScope(Dispatchers.Main).launch { controller.events.collect { events += it } }
    }

    @After
    fun tearDown() {
        listening?.cancel()
        runBlocking(Dispatchers.Main) {
            container.appPreferences.setGaplessQueue(gaplessBefore)
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
    }

    @Test
    fun theNextItemTakesOverWithNoRebuildAndTheFinishedOneIsPlayed() = runBlocking(Dispatchers.Main) {
        container.appPreferences.setGaplessQueue(true)
        val first = item("gapless-first", seconds = 4)
        val second = item("gapless-second", seconds = 30)
        queue.enqueue(second)
        queue.playNow(first)

        assertEquals("gapless-second", awaitCurrent("gapless-second"))
        awaitEvent { it is PlaybackEvent.CrossedOver }

        val crossed = events.filterIsInstance<PlaybackEvent.CrossedOver>()
        assertEquals(
            "the player must cross over by itself, not end and be rebuilt (events: $events)",
            listOf("gapless-first" to "gapless-second"),
            crossed.map { it.fromItemId.value to it.itemId.value },
        )
        assertTrue("no Ended for the first item when it crossed over", events.none { it is PlaybackEvent.Ended })
        assertEquals(1, queue.state.value.currentIndex)
        assertEquals(
            PlayState.Played,
            awaitPlayState(first.item.id) { it == PlayState.Played },
        )
        assertTrue("the second item keeps playing", awaitPlaying("gapless-second"))
    }

    @Test
    fun shortItemsCrossOverOneAfterAnother() = runBlocking(Dispatchers.Main) {
        container.appPreferences.setGaplessQueue(true)
        val first = item("gapless-short-1", seconds = 4)
        val second = item("gapless-short-2", seconds = 4)
        val third = item("gapless-short-3", seconds = 30)
        queue.enqueue(second)
        queue.enqueue(third)
        queue.playNow(first)

        assertEquals("gapless-short-3", awaitCurrent("gapless-short-3"))
        awaitEvent { it is PlaybackEvent.CrossedOver && it.itemId.value == "gapless-short-3" }

        assertEquals(
            "both hand-overs must cross over (events: $events)",
            listOf("gapless-short-1" to "gapless-short-2", "gapless-short-2" to "gapless-short-3"),
            events.filterIsInstance<PlaybackEvent.CrossedOver>().map { it.fromItemId.value to it.itemId.value },
        )
    }

    @Test
    fun theNextItemStartsWhereItWasLeft() = runBlocking(Dispatchers.Main) {
        container.appPreferences.setGaplessQueue(true)
        val first = item("gapless-first", seconds = 4)
        val second = item("gapless-resumed", seconds = 30)
        container.playbackProgressStore.save(second.item.id, RESUME_MS, 30_000)
        queue.enqueue(second)
        queue.playNow(first)

        assertEquals("gapless-resumed", awaitCurrent("gapless-resumed"))

        assertTrue("crossed over (events: $events)", awaitEvent { it is PlaybackEvent.CrossedOver })
        val at = controller.state.value?.positionMs ?: 0
        assertTrue("the second item must start at its resume point, was at ${at}ms", at >= RESUME_MS - TOLERANCE_MS)
    }

    @Test
    fun withGaplessOffTheQueueAdvancesAsBefore() = runBlocking(Dispatchers.Main) {
        container.appPreferences.setGaplessQueue(false)
        val first = item("gapless-off-first", seconds = 2)
        val second = item("gapless-off-second", seconds = 30)
        queue.enqueue(second)
        queue.playNow(first)

        assertEquals("gapless-off-second", awaitCurrent("gapless-off-second"))

        assertTrue(
            "no crossover with the setting off (events: $events)",
            events.none { it is PlaybackEvent.CrossedOver }
        )
        assertTrue("the first item ended the old way", events.any { it is PlaybackEvent.Ended })
    }

    private suspend fun awaitEvent(wanted: (PlaybackEvent) -> Boolean): Boolean = withTimeoutOrNull(TIMEOUT_MS) {
        while (events.none(wanted)) delay(POLL_MS)
        true
    } ?: false

    private suspend fun awaitCurrent(id: String): String? = withTimeoutOrNull(TIMEOUT_MS) {
        while (controller.state.value?.itemId?.value != id) delay(POLL_MS)
        id
    }

    private suspend fun awaitPlaying(id: String): Boolean = withTimeoutOrNull(TIMEOUT_MS) {
        while (controller.state.value?.let { it.itemId.value == id && it.isPlaying } != true) delay(POLL_MS)
        true
    } ?: false

    private suspend fun awaitPlayState(id: MediaItemId, wanted: (PlayState) -> Boolean): PlayState? =
        withTimeoutOrNull(TIMEOUT_MS) {
            var state = container.playbackProgressStore.playState(id)
            while (!wanted(state)) {
                delay(POLL_MS)
                state = container.playbackProgressStore.playState(id)
            }
            state
        }

    private fun item(id: String, seconds: Int): PlayableItem {
        val file = File(context.cacheDir, "$id.wav").apply { writeBytes(SilentWav.bytes(seconds)) }
        return PlayableItem(
            item = MediaItem(
                id = MediaItemId(id),
                sourceId = SourceId("test"),
                title = id,
                publishedAt = null,
                duration = null
            ),
            handle = PlayHandle.Podcast(file.absolutePath),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 50L
        const val RESUME_MS = 12_000L
        const val TOLERANCE_MS = 500L
    }
}
