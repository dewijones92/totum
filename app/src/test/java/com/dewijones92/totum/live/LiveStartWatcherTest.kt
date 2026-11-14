package com.dewijones92.totum.live

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.player.LiveCheck
import com.dewijones92.totum.innertube.player.UpcomingLive
import com.dewijones92.totum.operations.Ended
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.queue.RefusedTap
import com.dewijones92.totum.video.LivePhase
import com.dewijones92.totum.video.LiveStates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LiveStartWatcherTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val videoId = "abcdefghijk"
    private val title = "Practice session"

    private class Notices : LiveNotices {
        val live = mutableListOf<Triple<String, String, HttpUrl>>()
        val gaveUp = mutableListOf<Triple<String, String, Int>>()

        override fun isLive(videoId: String, title: String, watchUrl: HttpUrl): Boolean {
            live += Triple(videoId, title, watchUrl)
            return true
        }

        override fun gaveUp(videoId: String, title: String, hours: Int): Boolean {
            gaveUp += Triple(videoId, title, hours)
            return true
        }
    }

    private class Rig(
        val taps: MutableSharedFlow<RefusedTap>,
        val lives: LiveStates,
        val operations: Operations,
        val notices: Notices,
        val checkedAt: MutableList<Long>,
        val checkedUrls: MutableList<HttpUrl>,
        val ended: MutableList<Ended>,
        val answers: MutableList<LiveCheck>,
        var fallback: LiveCheck,
    )

    private fun TestScope.rig(
        vararg answers: LiveCheck,
        fallback: LiveCheck = LiveCheck.Unknown("nothing scripted"),
    ): Rig {
        val taps = MutableSharedFlow<RefusedTap>(extraBufferCapacity = 8)
        val lives = LiveStates { currentTime }
        val operations = Operations { currentTime }
        val notices = Notices()
        val rig = Rig(
            taps, lives, operations, notices, mutableListOf(), mutableListOf(), mutableListOf(),
            answers.toMutableList(), fallback,
        )
        backgroundScope.launch { operations.ended.collect { rig.ended += it } }
        LiveStartWatcher(
            scope = backgroundScope,
            lives = lives,
            check = { url ->
                rig.checkedAt += currentTime
                rig.checkedUrls += url
                if (rig.answers.isEmpty()) rig.fallback else rig.answers.removeAt(0)
            },
            operations = operations,
            notices = notices,
            subjectFor = { "Waiting for $it" },
            clock = { currentTime },
        ).follow(taps)
        runCurrent()
        return rig
    }

    private fun upcoming(startsAt: Instant? = null) = UpcomingLive(startsAt, "LIVE_STREAM_OFFLINE")

    private fun TestScope.refuse(rig: Rig, id: String? = videoId, name: String = title) {
        rig.taps.tryEmit(RefusedTap(title = name, reason = null, videoId = id))
        runCurrent()
    }

    private fun TestScope.runTo(ms: Long) {
        advanceTimeBy(ms - currentTime)
        runCurrent()
    }

    @Test
    fun `a tap on a stream that is not in the registry as upcoming starts no wait`() = runTest {
        val rig = rig(LiveCheck.Live("t"))

        refuse(rig)
        runTo(hour)

        assertTrue(rig.checkedAt.isEmpty())
        assertTrue(rig.operations.running.value.isEmpty())
        assertTrue(rig.notices.live.isEmpty())
    }

    @Test
    fun `a tap on a stream the registry knows is live starts no wait`() = runTest {
        val rig = rig(LiveCheck.Live("t"))
        rig.lives.noteLive(videoId, "test")

        refuse(rig)
        runTo(hour)

        assertTrue(rig.checkedAt.isEmpty())
    }

    @Test
    fun `a refusal with no video id starts no wait`() = runTest {
        val rig = rig(LiveCheck.Live("t"))
        rig.lives.noteUpcoming(videoId, upcoming(), "test")

        refuse(rig, id = null)
        runTo(hour)

        assertTrue(rig.checkedAt.isEmpty())
        assertTrue(rig.operations.running.value.isEmpty())
    }

    @Test
    fun `an event more than six hours away is not waited for`() = runTest {
        val rig = rig(LiveCheck.Live("t"))
        rig.lives.noteUpcoming(videoId, upcoming(Instant.ofEpochMilli(currentTime + 7 * hour)), "test")

        refuse(rig)
        runTo(2 * hour)

        assertTrue(rig.checkedAt.isEmpty())
        assertTrue(rig.operations.running.value.isEmpty())
    }

    @Test
    fun `an event six hours or less away is waited for, shown in the progress strip with its title`() = runTest {
        val rig = rig(fallback = LiveCheck.StillUpcoming(upcoming(), "t"))
        rig.lives.noteUpcoming(videoId, upcoming(Instant.ofEpochMilli(currentTime + 6 * hour)), "test")

        refuse(rig)

        val running = rig.operations.running.value
        assertEquals(1, running.size)
        assertEquals("live-wait:$videoId", running.single().key)
        assertEquals("Waiting for $title", running.single().subject)
    }

    @Test
    fun `an upcoming tap is checked on the schedule and the first live answer notifies once with the watch link`() =
        runTest {
            val rig = rig(LiveCheck.StillUpcoming(upcoming(), "t"), LiveCheck.Unknown("flaky"), LiveCheck.Live("tv"))
            rig.lives.noteUpcoming(videoId, upcoming(), "test")

            refuse(rig)
            runTo(2 * minute - 1)
            assertTrue("nothing is asked before the first 2 minutes are up", rig.checkedAt.isEmpty())
            runTo(2 * minute)
            assertEquals(listOf(2 * minute), rig.checkedAt)
            runTo(4 * minute)
            runTo(6 * minute)
            runTo(2 * hour)

            assertEquals(listOf(2 * minute, 4 * minute, 6 * minute), rig.checkedAt)
            assertEquals("https://www.youtube.com/watch?v=$videoId", rig.checkedUrls.first().value)
            assertEquals(1, rig.notices.live.size)
            assertEquals(videoId, rig.notices.live.single().first)
            assertEquals(title, rig.notices.live.single().second)
            assertTrue(rig.notices.gaveUp.isEmpty())
            assertEquals(LivePhase.LIVE, rig.lives.of(videoId)?.phase)
            assertTrue(rig.operations.running.value.isEmpty())
            assertEquals(OperationEnd.Done(1), rig.ended.single().end)
        }

    @Test
    fun `a stream that turns out to be a recording is offered for playing and marked ended`() = runTest {
        val rig = rig(LiveCheck.Ended("anon"))
        rig.lives.noteUpcoming(videoId, upcoming(), "test")

        refuse(rig)
        runTo(2 * minute)

        assertEquals(1, rig.notices.live.size)
        assertEquals(LivePhase.ENDED, rig.lives.of(videoId)?.phase)
        assertEquals(OperationEnd.Done(1), rig.ended.single().end)
    }

    @Test
    fun `a wait that never sees it go live gives up at six hours, clears it and says so`() = runTest {
        val rig = rig(fallback = LiveCheck.StillUpcoming(upcoming(), "t"))
        rig.lives.noteUpcoming(videoId, upcoming(), "test")

        refuse(rig)
        runTo(6 * hour - 1)
        assertTrue(rig.notices.gaveUp.isEmpty())
        assertEquals(LivePhase.UPCOMING, rig.lives.of(videoId)?.phase)
        runTo(6 * hour)

        assertEquals(listOf(Triple(videoId, title, 6)), rig.notices.gaveUp)
        assertNull(rig.lives.of(videoId))
        assertTrue(rig.notices.live.isEmpty())
        assertTrue("it stopped asking", rig.checkedAt.last() <= 6 * hour)
        val checks = rig.checkedAt.size
        runTo(12 * hour)
        assertEquals(checks, rig.checkedAt.size)
        val end = rig.ended.single().end
        assertTrue(end is OperationEnd.Failed && "6 hours" in end.why)
    }

    @Test
    fun `a check that cannot tell is asked again and never gives up early or notifies`() = runTest {
        val rig = rig(fallback = LiveCheck.Unknown("offline"))
        rig.lives.noteUpcoming(videoId, upcoming(), "test")

        refuse(rig)
        runTo(hour)

        assertTrue(rig.checkedAt.size > 5)
        assertTrue(rig.notices.live.isEmpty())
        assertTrue(rig.notices.gaveUp.isEmpty())
        assertEquals(1, rig.operations.running.value.size)
    }

    @Test
    fun `a second tap on the same stream does not start a second wait`() = runTest {
        val rig = rig(fallback = LiveCheck.StillUpcoming(upcoming(), "t"))
        rig.lives.noteUpcoming(videoId, upcoming(), "test")

        refuse(rig)
        refuse(rig)
        refuse(rig)
        runTo(2 * minute)

        assertEquals(1, rig.operations.running.value.size)
        assertEquals(listOf(2 * minute), rig.checkedAt)
    }

    @Test
    fun `two different streams are waited for side by side`() = runTest {
        val rig = rig(fallback = LiveCheck.StillUpcoming(upcoming(), "t"))
        rig.lives.noteUpcoming(videoId, upcoming(), "test")
        rig.lives.noteUpcoming("zyxwvutsrqp", upcoming(), "test")

        refuse(rig)
        refuse(rig, id = "zyxwvutsrqp", name = "Another")

        assertEquals(
            setOf("live-wait:$videoId", "live-wait:zyxwvutsrqp"),
            rig.operations.running.value.map { it.key }.toSet(),
        )
    }

    @Test
    fun `once a wait has ended, tapping the stream again waits again`() = runTest {
        val rig = rig(LiveCheck.Live("t"))
        rig.lives.noteUpcoming(videoId, upcoming(), "test")
        refuse(rig)
        runTo(2 * minute)
        assertTrue(rig.operations.running.value.isEmpty())

        rig.lives.noteUpcoming(videoId, upcoming(), "test")
        rig.answers += LiveCheck.Live("t")
        refuse(rig)
        runTo(4 * minute + 1)

        assertEquals(2, rig.notices.live.size)
    }

    @Test
    fun `cancelling from the progress strip stops the checks and sends no notice`() = runTest {
        val rig = rig(fallback = LiveCheck.StillUpcoming(upcoming(), "t"))
        rig.lives.noteUpcoming(videoId, upcoming(), "test")
        refuse(rig)
        runTo(2 * minute)
        assertEquals(1, rig.checkedAt.size)

        rig.operations.cancel(rig.operations.running.value.single().id)
        runCurrent()
        runTo(hour)

        assertEquals(1, rig.checkedAt.size)
        assertTrue(rig.notices.live.isEmpty())
        assertTrue(rig.notices.gaveUp.isEmpty())
        assertTrue(rig.operations.running.value.isEmpty())
        assertTrue(rig.ended.single().end is OperationEnd.Cancelled)

        rig.lives.noteUpcoming(videoId, upcoming(), "test")
        refuse(rig)
        assertEquals("a cancelled wait frees the video for a new one", 1, rig.operations.running.value.size)
    }

    @Test
    fun `waiting by hand for a video id that is not a real one does nothing`() = runTest {
        val rig = rig(LiveCheck.Live("t"))
        rig.lives.noteUpcoming("short", upcoming(), "test")

        refuse(rig, id = "short")
        runTo(hour)

        assertTrue(rig.checkedAt.isEmpty())
        assertTrue(rig.operations.running.value.isEmpty())
    }
}
