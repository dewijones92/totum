package com.dewijones92.totum.treat

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetSleep
import com.dewijones92.totum.data.targets.TargetSleepAsk
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.pins.Pin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun treatItem(id: String) = PlayableItem(
    MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("s"),
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://x.test/$id.mp4"),
    ),
    PlayHandle.Podcast(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TreatTest {

    private val pin = Pin.Playlist("PL1", "Toy Story", null, music = false)
    private val lg = Target("lg", "LG TV", TargetKind.TV)

    private fun state(
        canSwitchOff: Boolean = true,
        playing: Boolean = false,
        starting: Boolean = false,
        sleep: TargetSleep? = null,
    ) = TargetState(lg, playing = playing, starting = starting, canSwitchOff = canSwitchOff, sleep = sleep)

    private class Harness(scope: TestScope, canSwitchOff: Boolean, listed: Boolean) {
        val targets = MutableStateFlow(
            if (listed) {
                listOf(TargetState(Target("lg", "LG TV", TargetKind.TV), canSwitchOff = canSwitchOff))
            } else {
                emptyList()
            },
        )
        val played = mutableListOf<Pair<String, TreatContent>>()
        val armed = mutableListOf<Pair<String, TargetSleepAsk>>()
        val stopped = mutableListOf<String>()
        val notices = mutableListOf<String>()
        var playAnswer: HomeAnswer<TargetState> = HomeAnswer.Ok(
            TargetState(Target("lg", "LG TV", TargetKind.TV), playing = true, canSwitchOff = canSwitchOff),
        )
        var armAnswer: HomeAnswer<TargetState> = HomeAnswer.Ok(
            TargetState(Target("lg", "LG TV", TargetKind.TV), playing = true, canSwitchOff = canSwitchOff),
        )
        var contentAnswer: TreatContentResult = TreatContentResult.Found(
            TreatContent("Toy Story", listOf(treatItem("film")), "playlist:PL1", "Toy Story"),
        )
        val operations = Operations { scope.testScheduler.currentTime }

        val ports = TreatPorts(
            targets = targets,
            fadeSeconds = { 20 },
            content = { contentAnswer },
            play = { id, content ->
                played += id to content
                playAnswer
            },
            awaitStarted = { targets.value.firstOrNull() },
            arm = { id, ask ->
                armed += id to ask
                armAnswer
            },
            stopNow = { id ->
                stopped += id
                HomeAnswer.Ok(TargetState(Target("lg", "LG TV", TargetKind.TV)))
            },
            subject = { title, tv -> "$title on $tv" },
            detail = { minutes, end -> "$minutes min left, then $end" },
            notice = object : TreatNotice {
                override fun on(title: String, tv: String, endsAtMs: Long) {
                    notices += "on $title $tv $endsAtMs"
                }

                override fun off(why: String) {
                    notices += "off $why"
                }
            },
        )
    }

    private fun TestScope.harness(canSwitchOff: Boolean = true, listed: Boolean = true) =
        Harness(this, canSwitchOff, listed)

    private fun TestScope.treat(
        h: Harness,
        store: TreatStore = InMemoryTreatStore(
            TreatChoice(pinKey = pin.key, targetId = "lg", targetLabel = "LG TV"),
        ),
    ) = Treat(store, { listOf(pin) }, h.ports, h.operations, backgroundScope, clock = { testScheduler.currentTime })

    private fun TestScope.ticks(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `a tap with nothing chosen does not touch the home server`() = runTest {
        val h = harness()
        val treat = treat(h, InMemoryTreatStore())

        treat.tap("a test")
        runCurrent()

        assertEquals(TreatStatus.NotSetUp, treat.status.value)
        assertTrue(h.played.isEmpty() && h.armed.isEmpty())
    }

    @Test
    fun `a tap starts the saved defaults at once, and the home server is asked to time it`() = runTest {
        val h = harness()
        val treat = treat(h)

        treat.tap("a test")
        runCurrent()

        assertEquals(listOf("lg"), h.played.map { it.first })
        val (id, ask) = h.armed.single()
        assertEquals("lg", id)
        assertEquals(
            TargetSleepAsk(SleepKind.AFTER_TIME, seconds = 15 * 60, fadeSeconds = 20, switchOff = true, stop = false),
            ask,
        )
        val on = treat.status.value as TreatStatus.On
        assertEquals(15 * 60_000L, on.endsAtMs)
        assertFalse(on.fellBack)
        assertEquals(listOf("on Toy Story LG TV 900000"), h.notices)
    }

    @Test
    fun `a start with other settings for this time leaves the saved defaults alone`() = runTest {
        val h = harness()
        val store = InMemoryTreatStore(TreatChoice(pinKey = pin.key, targetId = "lg", targetLabel = "LG TV"))
        val treat = treat(h, store)

        treat.start("the sheet", override = treat.choice.value.copy(minutes = 30, end = TreatEnd.PAUSE, fade = false))
        runCurrent()

        val ask = h.armed.single().second
        assertEquals(30 * 60, ask.seconds)
        assertEquals(0, ask.fadeSeconds)
        assertFalse(ask.switchOff || ask.stop)
        assertEquals(TreatChoice(pinKey = pin.key, targetId = "lg", targetLabel = "LG TV"), store.read())
        assertEquals(15, treat.choice.value.minutes)
    }

    @Test
    fun `saving defaults changes what the next tap asks for`() = runTest {
        val h = harness()
        val treat = treat(h)

        treat.choose(treat.choice.value.copy(minutes = 45, end = TreatEnd.STOP), "a test")
        treat.tap("a test")
        runCurrent()

        val ask = h.armed.single().second
        assertEquals(45 * 60, ask.seconds)
        assertTrue(ask.stop)
        assertFalse(ask.switchOff)
    }

    @Test
    fun `a TV that cannot be switched off is stopped instead, and the status says so`() = runTest {
        val h = harness(canSwitchOff = false)
        val treat = treat(h)

        treat.tap("a test")
        runCurrent()

        val ask = h.armed.single().second
        assertTrue("stop instead", ask.stop)
        assertFalse("never asks for what it cannot do", ask.switchOff)
        val on = treat.status.value as TreatStatus.On
        assertTrue(on.fellBack)
        assertEquals(TreatEnd.STOP, on.end)
    }

    @Test
    fun `when the timer cannot be set the TV is stopped so nothing is left running with no end`() = runTest {
        val h = harness()
        h.armAnswer = HomeAnswer.No("HTTP 503")
        val store = InMemoryTreatStore(TreatChoice(pinKey = pin.key, targetId = "lg", targetLabel = "LG TV"))
        val treat = treat(h, store)

        treat.tap("a test")
        runCurrent()

        assertEquals(listOf("lg"), h.stopped)
        val failed = treat.status.value as TreatStatus.Failed
        assertTrue(failed.failure is TreatFailure.TimerNotSet)
        assertFalse(store.readRunning().on)
    }

    @Test
    fun `a TV the home server will not play on is left alone, nothing is stopped on it`() = runTest {
        val h = harness()
        h.playAnswer = HomeAnswer.No("busy", 409)
        val treat = treat(h)

        treat.tap("a test")
        runCurrent()

        assertTrue("it never took ours, so it is not ours to stop", h.stopped.isEmpty())
        assertTrue(h.armed.isEmpty())
        assertTrue((treat.status.value as TreatStatus.Failed).failure is TreatFailure.WontTake)
    }

    @Test
    fun `a TV the home server does not list is a failure that names what it did list`() = runTest {
        val h = harness(listed = false)
        val treat = treat(h)

        treat.tap("a test")
        runCurrent()

        val failure = (treat.status.value as TreatStatus.Failed).failure
        assertEquals(TreatFailure.TvNotFound("LG TV", emptyList()), failure)
        assertTrue(h.played.isEmpty())
    }

    @Test
    fun `the strip shows the treat with its time left and stopping it stops the TV and ends the timer`() = runTest {
        val h = harness()
        val treat = treat(h)
        treat.tap("a test")
        runCurrent()
        ticks(6_000)

        val shown = h.operations.running.value.single()
        assertEquals(OperationKind.TREAT, shown.kind)
        assertEquals("Toy Story on LG TV", shown.subject)
        assertEquals("15 min left, then SWITCH_OFF", shown.detail)

        h.operations.cancel(shown.id)
        runCurrent()

        assertEquals(listOf("lg"), h.stopped)
        assertEquals(TreatStatus.Ready, treat.status.value)
        assertTrue(h.operations.running.value.isEmpty())
    }

    @Test
    fun `stopping from the tile does the same`() = runTest {
        val h = harness()
        val treat = treat(h)
        treat.tap("a test")
        runCurrent()

        treat.tap("a second tap")
        runCurrent()

        assertEquals(listOf("lg"), h.stopped)
        assertEquals(TreatStatus.Ready, treat.status.value)
        assertEquals("off stopped: tapped from a second tap", h.notices.last())
    }

    @Test
    fun `when time is up the treat is over and the home server did the ending`() = runTest {
        val h = harness()
        val store = InMemoryTreatStore(TreatChoice(pinKey = pin.key, targetId = "lg", targetLabel = "LG TV"))
        val treat = treat(h, store)
        treat.tap("a test")
        runCurrent()
        h.targets.value = listOf(state(playing = true, sleep = TargetSleep(SleepKind.AFTER_TIME, remainingMs = 1)))

        ticks(15 * 60_000L + 1_000)

        assertTrue("the phone stopped nothing: the timer on the home server ends it", h.stopped.isEmpty())
        assertEquals(TreatStatus.Ready, treat.status.value)
        assertFalse(store.readRunning().on)
    }

    @Test
    fun `a treat whose timer has vanished from the home server ends early instead of counting down`() =
        runTest {
            val h = harness()
            val treat = treat(h)
            treat.tap("a test")
            runCurrent()
            h.targets.value = listOf(state(playing = false, sleep = null))

            ticks(60_000)

            assertEquals(TreatStatus.Ready, treat.status.value)
            assertTrue(h.operations.running.value.isEmpty())
        }

    @Test
    fun `after the app restarts a treat with time left is watched again, and one that is over is cleared`() = runTest {
        val h = harness()
        val running = TreatRunning("lg", "LG TV", "Toy Story", endsAtMs = 10 * 60_000L, end = TreatEnd.SWITCH_OFF)
        val store = InMemoryTreatStore(TreatChoice(pinKey = pin.key, targetId = "lg"), running)
        val resumed = treat(h, store)

        resumed.resume()
        runCurrent()

        val on = resumed.status.value as TreatStatus.On
        assertEquals("Toy Story", on.title)
        assertEquals(10 * 60_000L, on.endsAtMs)
        assertTrue(h.played.isEmpty() && h.armed.isEmpty())
        assertEquals(OperationKind.TREAT, h.operations.running.value.single().kind)

        val late = InMemoryTreatStore(
            TreatChoice(pinKey = pin.key, targetId = "lg"),
            TreatRunning("lg", "LG TV", "Toy Story", endsAtMs = -1, end = TreatEnd.STOP),
        )
        val over = treat(harness(), late)
        over.resume()
        runCurrent()
        assertFalse(late.readRunning().on)
        assertNull((over.status.value as? TreatStatus.On))
    }
}
