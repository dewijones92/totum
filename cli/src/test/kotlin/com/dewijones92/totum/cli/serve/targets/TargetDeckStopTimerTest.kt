package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetSleepAsk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class TargetDeckStopTimerTest {

    private val player = RecordingPlayer()
    private val link = ConnectingLink()
    private val dir = Files.createTempDirectory("deck-stop-timer").toFile()
    private val sounds = TargetSounds { item, _ -> "https://sound/${item.item.id.value}" }

    private fun TestScope.deck() = TargetDeck(
        Target("lg-tv", "LG TV", TargetKind.TV),
        player,
        link,
        sounds,
        dir.resolve("tv.json"),
        clock = { testScheduler.currentTime },
        scope = backgroundScope,
    )

    private fun after(seconds: Int, stop: Boolean) = TargetControl(
        TargetAction.SLEEP,
        sleep = TargetSleepAsk(SleepKind.AFTER_TIME, seconds, fadeSeconds = 0, stop = stop),
    )

    private suspend fun TestScope.moveTo(ms: Long) {
        advanceTimeBy(ms - testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun `a timer asked to stop pauses at the end, then stops and hands the target back`() = runTest {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(podcastItem("a"), podcastItem("b")), QueueMode.REPLACE))
        deck.control(after(30, stop = true))
        runCurrent()
        assertFalse(player.stopped)

        moveTo(32_000)

        assertTrue("paused first", player.paused)
        assertTrue("then stopped", player.stopped)
        assertFalse("and the target was handed back", deck.state().linked)
        assertFalse(deck.state().playing)
        assertNull("the timer is spent", deck.state().sleep)
    }

    @Test
    fun `a timer that is not asked to stop only pauses`() = runTest {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(podcastItem("a")), QueueMode.REPLACE))
        deck.control(after(30, stop = false))
        runCurrent()

        moveTo(32_000)

        assertTrue(player.paused)
        assertFalse("left as it was, resumable", player.stopped)
        assertTrue("and still linked", deck.state().linked)
    }
}
