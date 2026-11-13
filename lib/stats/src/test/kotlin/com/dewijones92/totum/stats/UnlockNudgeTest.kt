package com.dewijones92.totum.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UnlockNudgeTest {

    private val london = ZoneId.of("Europe/London")
    private val minute = 60_000L
    private val on = UnlockNudgeSettings(enabled = true, withinMinutes = 10)

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun decide(
        settings: UnlockNudgeSettings,
        unlocks: List<String>,
        triggeredAt: String,
        blocks: Set<NudgeBlock> = emptySet(),
    ) = settings.decide(unlocks.map(::at), at(triggeredAt), london, blocks)

    private fun skipReason(decision: UnlockNudgeDecision) = (decision as UnlockNudgeDecision.Skip).why

    @Test
    fun `it fires when the last unlock was inside the window, saying how long ago and how many today`() {
        val decision = decide(
            on,
            unlocks = listOf("2026-11-13T09:00:00Z", "2026-11-13T09:20:00Z", "2026-11-13T09:23:00Z"),
            triggeredAt = "2026-11-13T09:23:00Z",
        )

        assertEquals(UnlockNudgeDecision.Fire(sinceLastMs = 3 * minute, unlocksToday = 3), decision)
    }

    @Test
    fun `it never fires while switched off, however close the unlocks`() {
        val decision = decide(
            on.copy(enabled = false),
            listOf("2026-11-13T09:20:00Z", "2026-11-13T09:21:00Z"),
            "2026-11-13T09:21:00Z",
        )

        assertTrue(skipReason(decision).contains("switched off"))
    }

    @Test
    fun `it fires at exactly the window and not a second past it`() {
        val atEdge = decide(on, listOf("2026-11-13T09:00:00Z", "2026-11-13T09:10:00Z"), "2026-11-13T09:10:00Z")
        val past = decide(on, listOf("2026-11-13T09:00:00Z", "2026-11-13T09:10:01Z"), "2026-11-13T09:10:01Z")

        assertTrue(atEdge is UnlockNudgeDecision.Fire)
        assertTrue(skipReason(past).contains("10"))
    }

    @Test
    fun `the window is the setting's, not a fixed ten minutes`() {
        val unlocks = listOf("2026-11-13T09:00:00Z", "2026-11-13T09:25:00Z")

        assertTrue(decide(on.copy(withinMinutes = 30), unlocks, "2026-11-13T09:25:00Z") is UnlockNudgeDecision.Fire)
        assertTrue(decide(on.copy(withinMinutes = 2), unlocks, "2026-11-13T09:25:00Z") is UnlockNudgeDecision.Skip)
    }

    @Test
    fun `the first unlock on record has nothing before it, so it does not fire`() {
        val decision = decide(on, listOf("2026-11-13T09:00:00Z"), "2026-11-13T09:00:00Z")

        assertTrue(skipReason(decision).contains("no earlier unlock"))
    }

    @Test
    fun `a second event for the same unlock is not an earlier unlock`() {
        val decision = decide(
            on,
            listOf("2026-11-13T09:00:00Z", "2026-11-13T09:00:00.500Z"),
            "2026-11-13T09:00:01Z",
        )

        assertTrue(skipReason(decision).contains("no earlier unlock"))
    }

    @Test
    fun `when the unlock that woke us is not on record yet, it is counted as the current one`() {
        val decision = decide(on, listOf("2026-11-13T09:00:00Z"), triggeredAt = "2026-11-13T09:04:00Z")

        assertEquals(UnlockNudgeDecision.Fire(sinceLastMs = 4 * minute, unlocksToday = 2), decision)
    }

    @Test
    fun `an unlock a little before the trigger is the current one, not the previous`() {
        val decision = decide(
            on,
            listOf("2026-11-13T09:00:00Z", "2026-11-13T09:05:00Z"),
            triggeredAt = "2026-11-13T09:05:02Z",
        )

        assertEquals(UnlockNudgeDecision.Fire(sinceLastMs = 5 * minute, unlocksToday = 2), decision)
    }

    @Test
    fun `each block holds the nudge back and is named in the reason`() {
        val unlocks = listOf("2026-11-13T09:00:00Z", "2026-11-13T09:05:00Z")
        NudgeBlock.entries.forEach { block ->
            val reason = skipReason(decide(on, unlocks, "2026-11-13T09:05:00Z", setOf(block)))
            assertTrue("$block: $reason", reason.contains(block.reason))
        }
    }

    @Test
    fun `two blocks are both named`() {
        val reason = skipReason(
            decide(
                on,
                listOf("2026-11-13T09:00:00Z", "2026-11-13T09:05:00Z"),
                "2026-11-13T09:05:00Z",
                setOf(NudgeBlock.CALL, NudgeBlock.EXSURGE),
            ),
        )

        assertTrue(reason.contains(NudgeBlock.CALL.reason) && reason.contains(NudgeBlock.EXSURGE.reason))
    }

    @Test
    fun `a block only matters inside the window, outside it the reason is the window`() {
        val reason = skipReason(
            decide(
                on,
                listOf("2026-11-13T07:00:00Z", "2026-11-13T09:05:00Z"),
                "2026-11-13T09:05:00Z",
                setOf(NudgeBlock.CALL),
            ),
        )

        assertFalse(reason.contains(NudgeBlock.CALL.reason))
    }

    @Test
    fun `unlocks today are counted in the phone's day, so a late unlock after local midnight starts again at one`() {
        val decision = decide(
            on,
            listOf("2026-07-01T22:00:00Z", "2026-07-01T22:30:00Z", "2026-07-01T23:05:00Z", "2026-07-01T23:10:00Z"),
            "2026-07-01T23:10:00Z",
        )

        assertEquals(UnlockNudgeDecision.Fire(sinceLastMs = 5 * minute, unlocksToday = 2), decision)
    }

    @Test
    fun `the setting is kept between 2 and 30 minutes and the codec ignores keys it does not know`() {
        assertEquals(2, UnlockNudgeSettings(withinMinutes = 0).validated().withinMinutes)
        assertEquals(30, UnlockNudgeSettings(withinMinutes = 90).validated().withinMinutes)
        assertEquals(UnlockNudgeSettings(), UnlockNudgeCodec.decode("""{"nothing":1}"""))
        assertEquals(on, UnlockNudgeCodec.decode(UnlockNudgeCodec.encode(on)))
        assertFalse(UnlockNudgeSettings().enabled)
        assertEquals(10, UnlockNudgeSettings().withinMinutes)
    }

    @Test
    fun `continue is held for five seconds and the countdown says how many are left`() {
        assertEquals(5, continueInSeconds(shownAt = 1_000, now = 1_000))
        assertEquals(3, continueInSeconds(shownAt = 1_000, now = 3_001))
        assertEquals(1, continueInSeconds(shownAt = 1_000, now = 5_999))
        assertEquals(0, continueInSeconds(shownAt = 1_000, now = 6_000))
        assertEquals(0, continueInSeconds(shownAt = 1_000, now = 60_000))
        assertEquals(5, continueInSeconds(shownAt = 10_000, now = 9_000))
    }
}
