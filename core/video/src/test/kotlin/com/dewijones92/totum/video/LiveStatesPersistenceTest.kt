package com.dewijones92.totum.video

import com.dewijones92.totum.innertube.player.UpcomingLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class LiveStatesPersistenceTest {
    private var clock = 1_000_000_000L
    private val file = File.createTempFile("live-states", ".json").also { it.deleteOnExit() }

    private fun states() = LiveStates(FileLiveStatesStore(file)) { clock }

    @Test
    fun `upcoming and live entries come back after a restart`() {
        val first = states()
        first.noteUpcoming("up", UpcomingLive(Instant.ofEpochMilli(clock + 3_600_000), "in an hour"), "test")
        first.noteLive("live", "test")
        first.noteEnded("ended", "test")

        val second = states()

        assertEquals(LivePhase.UPCOMING, second.of("up")?.phase)
        assertEquals(Instant.ofEpochMilli(clock + 3_600_000), second.of("up")?.startsAt)
        assertEquals("in an hour", second.of("up")?.said)
        assertEquals(LivePhase.LIVE, second.of("live")?.phase)
        assertNull(second.of("ended"))
        assertEquals("2", second.diagnostics().getValue("live.restoredAtStart"))
    }

    @Test
    fun `a live entry not seen for twelve hours is not restored`() {
        states().noteLive("live", "test")
        clock += 13 * 60 * 60_000L

        assertNull(states().of("live"))
    }

    @Test
    fun `an upcoming entry is kept until two days after its scheduled start`() {
        val start = Instant.ofEpochMilli(clock + 60_000)
        states().noteUpcoming("up", UpcomingLive(start, "soon"), "test")

        clock += 47 * 60 * 60_000L
        assertEquals(LivePhase.UPCOMING, states().of("up")?.phase)

        clock += 2 * 60 * 60_000L
        assertNull(states().of("up"))
    }

    @Test
    fun `an upcoming entry with no start time follows the twelve hour rule`() {
        states().noteUpcoming("up", UpcomingLive(null, "sometime"), "test")
        clock += 11 * 60 * 60_000L
        assertEquals(LivePhase.UPCOMING, states().of("up")?.phase)
        clock += 2 * 60 * 60_000L
        assertNull(states().of("up"))
    }

    @Test
    fun `a cleared entry stays gone after a restart`() {
        val first = states()
        first.noteLive("live", "test")
        first.cleared("live", "test")

        assertNull(states().of("live"))
    }

    @Test
    fun `an unreadable file starts with nothing instead of failing`() {
        file.writeText("not json")

        assertTrue(states().states.value.isEmpty())
    }

    @Test
    fun `the report block says what is held`() {
        val live = states()
        live.noteLive("a", "test")
        live.noteUpcoming("b", UpcomingLive(null, "soon"), "test")

        val report = live.diagnostics()

        assertEquals("upcoming=1 live=1 ended=0", report.getValue("live.count"))
        assertTrue(report.getValue("live.entries").contains("a live"))
        assertTrue(report.getValue("live.entries").contains("b upcoming"))
    }
}
