package com.dewijones92.totum.live

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.player.LiveCheck
import com.dewijones92.totum.innertube.player.UpcomingLive
import com.dewijones92.totum.video.LiveStates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveRefresherTest {

    private val minute = 60_000L

    private fun id(n: Int) = "video%06d".format(n).take(11)

    private fun item(n: Int, kind: MediaContentKind) = MediaItem(
        id = MediaItemId("https://www.youtube.com/watch?v=${id(n)}"),
        sourceId = SourceId("s"),
        title = "Stream $n",
        publishedAt = null,
        duration = null,
        contentKind = kind,
    )

    private class Rig(
        val refresher: LiveRefresher,
        val states: LiveStates,
        val asked: MutableList<String>,
        val learned: MutableList<MediaItem>,
        var answer: (String) -> LiveCheck,
        var queue: List<MediaItem>,
    )

    private fun TestScope.rig(
        queued: List<MediaItem> = emptyList(),
        answer: (String) -> LiveCheck = {
            LiveCheck.Live(
                "t"
            )
        }
    ): Rig {
        val states = LiveStates { currentTime }
        val asked = mutableListOf<String>()
        val learned = mutableListOf<MediaItem>()
        lateinit var rig: Rig
        val refresher = LiveRefresher(
            scope = backgroundScope,
            states = states,
            queued = { rig.queue },
            check = { url ->
                asked += url.value.substringAfter("v=")
                rig.answer(url.value.substringAfter("v="))
            },
            onLearned = { learned += it },
            clock = { currentTime },
        )
        rig = Rig(refresher, states, asked, learned, answer, queued)
        return rig
    }

    @Test
    fun `only queued items tagged live or starting soon are asked, an ordinary queued video is not`() = runTest {
        val rig =
            rig(
                listOf(
                    item(1, MediaContentKind.LIVE),
                    item(2, MediaContentKind.STANDARD),
                    item(3, MediaContentKind.WAS_LIVE)
                )
            )
        rig.states.noteUpcoming(id(2), UpcomingLive(null, "soon"), "test")
        advanceTimeBy(10 * minute)

        rig.refresher.runCycle()

        assertEquals(listOf(id(1), id(2)).sorted(), rig.asked.sorted())
    }

    @Test
    fun `an item on screen is asked even when it is not queued, unless the registry already says it ended`() = runTest {
        val rig = rig()
        rig.states.noteEnded(id(2), "test")
        advanceTimeBy(10 * minute)
        rig.refresher.shown(id(1))
        rig.refresher.shown(id(2))

        rig.refresher.runCycle()

        assertEquals(listOf(id(1)), rig.asked)
    }

    @Test
    fun `an item shown twice stays shown until it has been hidden twice`() = runTest {
        val rig = rig()
        rig.refresher.shown(id(1))
        rig.refresher.shown(id(1))
        rig.refresher.hidden(id(1))
        rig.refresher.runCycle()
        assertEquals(listOf(id(1)), rig.asked)

        rig.refresher.hidden(id(1))
        rig.asked.clear()
        advanceTimeBy(10 * minute)
        rig.refresher.runCycle()
        assertTrue(rig.asked.isEmpty())
    }

    @Test
    fun `nothing is asked again within four minutes and is asked once four have passed`() = runTest {
        val rig = rig(listOf(item(1, MediaContentKind.LIVE)))
        rig.states.noteLive(id(1), "test")

        advanceTimeBy(4 * minute - 1)
        rig.refresher.runCycle()
        assertTrue(rig.asked.isEmpty())

        advanceTimeBy(1)
        rig.refresher.runCycle()
        assertEquals(listOf(id(1)), rig.asked)
    }

    @Test
    fun `at most six are asked in one cycle and the longest unasked go first`() = runTest {
        val rig = rig((1..9).map { item(it, MediaContentKind.LIVE) })
        (1..9).forEach { n ->
            rig.states.noteLive(id(n), "test")
            advanceTimeBy(1)
        }
        advanceTimeBy(10 * minute)

        rig.refresher.runCycle()

        assertEquals(6, rig.asked.size)
        assertEquals((1..6).map { id(it) }, rig.asked)
    }

    @Test
    fun `a queued stream learned to have ended is handed back as was live`() = runTest {
        val rig = rig(listOf(item(1, MediaContentKind.LIVE))) { LiveCheck.Ended("t") }

        rig.refresher.runCycle()

        assertEquals(listOf(MediaContentKind.WAS_LIVE), rig.learned.map { it.contentKind })
        assertEquals(item(1, MediaContentKind.LIVE).id, rig.learned.single().id)
    }

    @Test
    fun `a queued stream learned to be live is handed back as live`() = runTest {
        val rig = rig(listOf(item(1, MediaContentKind.LIVE))) { LiveCheck.Live("t") }

        rig.refresher.runCycle()

        assertEquals(listOf(MediaContentKind.LIVE), rig.learned.map { it.contentKind })
    }

    @Test
    fun `still upcoming and unknown answers teach nothing`() = runTest {
        val rig = rig(listOf(item(1, MediaContentKind.LIVE), item(2, MediaContentKind.LIVE))) { videoId ->
            if (videoId == id(1)) LiveCheck.StillUpcoming(UpcomingLive(null, "soon"), "t") else LiveCheck.Unknown("no")
        }

        rig.refresher.runCycle()

        assertTrue(rig.learned.isEmpty())
    }

    @Test
    fun `an item that is only on screen is asked about but there is no queue entry to correct`() = runTest {
        val rig = rig { LiveCheck.Ended("t") }
        rig.refresher.shown(id(1))

        rig.refresher.runCycle()

        assertEquals(listOf(id(1)), rig.asked)
        assertTrue(rig.learned.isEmpty())
    }

    @Test
    fun `a check that throws is an unknown answer and the cycle goes on`() = runTest {
        val rig = rig(listOf(item(1, MediaContentKind.LIVE), item(2, MediaContentKind.LIVE))) { videoId ->
            if (videoId == id(1)) error("boom") else LiveCheck.Ended("t")
        }

        val answered = rig.refresher.runCycle()

        assertTrue(answered)
        assertEquals(listOf(MediaContentKind.WAS_LIVE), rig.learned.map { it.contentKind })
    }

    @Test
    fun `a cycle where every answer was unknown says so, and one with nothing to ask does not`() = runTest {
        val unknown = rig(listOf(item(1, MediaContentKind.LIVE))) { LiveCheck.Unknown("no") }
        assertFalse(unknown.refresher.runCycle())

        val nothing = rig(listOf(item(2, MediaContentKind.STANDARD)))
        assertTrue(nothing.refresher.runCycle())
    }

    @Test
    fun `while every answer is unknown the cycles back off, and the first answer brings them back`() = runTest {
        val rig = rig(listOf(item(1, MediaContentKind.LIVE))) { LiveCheck.Unknown("no") }
        rig.refresher.start()

        advanceTimeBy(2 * minute)
        runCurrent()
        assertEquals("first cycle after two minutes", 1, rig.asked.size)

        advanceTimeBy(9 * minute)
        runCurrent()
        assertEquals("the next waits ten, not five", 1, rig.asked.size)

        advanceTimeBy(1 * minute)
        runCurrent()
        assertEquals(2, rig.asked.size)

        advanceTimeBy(19 * minute)
        runCurrent()
        assertEquals("then twenty", 2, rig.asked.size)

        advanceTimeBy(1 * minute)
        runCurrent()
        assertEquals(3, rig.asked.size)
    }
}
