package com.dewijones92.totum.data.queue

import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class GroupTimesLeftTest {

    private val peep = QueueGroup("peep", "Peep Show")
    private val other = QueueGroup("other", "Other")

    private fun entry(id: String, group: QueueGroup?, length: Duration? = 30.minutes) = QueueEntry(
        PlayableItem(
            MediaItem(MediaItemId(id), SourceId("s"), id, publishedAt = null, duration = length),
            PlayHandle.Podcast(),
        ),
        group,
    )

    private fun times(
        entries: List<QueueEntry>,
        from: Int = 0,
        speed: Float = 1f,
        countsAsLeft: (PlayableItem) -> Boolean = { true },
        states: Map<String, PlayState> = emptyMap(),
    ) = groupTimesLeft(entries, from, speed, countsAsLeft) { states[it.value] ?: PlayState.Unplayed }

    @Test
    fun `each group gets its own total and ungrouped entries get none`() {
        val entries = listOf(
            entry("a", peep),
            entry("loose", null),
            entry("b", peep),
            entry("c", other, 10.minutes),
        )

        val result = times(entries)

        assertEquals(setOf("peep", "other"), result.keys)
        assertEquals(60.minutes, result.getValue("peep").duration)
        assertEquals(10.minutes, result.getValue("other").duration)
    }

    @Test
    fun `only entries from the current place onwards count, the playing one included`() {
        val entries = listOf(entry("a", peep), entry("b", peep), entry("c", peep))

        assertEquals(90.minutes, times(entries, from = 0).getValue("peep").duration)
        assertEquals(60.minutes, times(entries, from = 1).getValue("peep").duration)
        assertEquals(30.minutes, times(entries, from = 2).getValue("peep").duration)
        assertFalse(times(entries, from = 3).containsKey("peep"))
    }

    @Test
    fun `entries the queue would pass over are left out of what is left`() {
        val entries = listOf(entry("done", peep), entry("b", peep), entry("c", peep))

        val result = times(entries, countsAsLeft = { it.item.id.value != "done" })

        assertEquals(60.minutes, result.getValue("peep").duration)
    }

    @Test
    fun `a group whose every entry is passed over has no line at all`() {
        val entries = listOf(entry("a", peep), entry("b", other))

        val result = times(entries, countsAsLeft = { it.item.id.value == "b" })

        assertEquals(setOf("other"), result.keys)
    }

    @Test
    fun `the chosen speed shortens the time and is reported with it`() {
        val result = times(listOf(entry("a", peep), entry("b", peep)), speed = 2f).getValue("peep")

        assertEquals(30.minutes, result.duration)
        assertEquals(2f, result.speed)
    }

    @Test
    fun `a group with an unknown length says at least and a group with none known says nothing`() {
        val entries = listOf(
            entry("a", peep),
            entry("b", peep, length = null),
            entry("c", other, length = null),
        )

        val result = times(entries)

        assertTrue(result.getValue("peep").atLeast)
        assertEquals(1, result.getValue("peep").unknown)
        assertFalse(result.containsKey("other"))
    }

    @Test
    fun `progress already made counts, per entry`() {
        val entries = listOf(entry("a", peep), entry("b", peep))
        val states = mapOf("a" to PlayState.InProgress(20 * 60_000L, 30 * 60_000L))

        assertEquals(40.minutes, times(entries, states = states).getValue("peep").duration)
    }

    @Test
    fun `a split group is one group`() {
        val entries = listOf(entry("a", peep), entry("x", other), entry("b", peep))

        assertEquals(60.minutes, times(entries).getValue("peep").duration)
    }

    @Test
    fun `an empty queue has no groups`() {
        assertTrue(times(emptyList()).isEmpty())
    }
}
