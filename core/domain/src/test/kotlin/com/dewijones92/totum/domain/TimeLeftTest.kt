package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TimeLeftTest {

    private fun item(
        id: String,
        length: Duration? = 60.minutes,
        kind: MediaContentKind = MediaContentKind.STANDARD,
    ) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = id,
        publishedAt = null,
        duration = length,
        contentKind = kind,
    )

    private fun left(items: List<MediaItem>, speed: Float = 1f, states: Map<String, PlayState> = emptyMap()) =
        timeLeft(items, speed) { states[it.value] ?: PlayState.Unplayed }

    @Test
    fun `unplayed items add up at normal speed with nothing unknown and no speed to mention`() {
        val result = left(listOf(item("a"), item("b"), item("c")))!!

        assertEquals(180.minutes, result.duration)
        assertEquals(0, result.unknown)
        assertFalse(result.atLeast)
        assertNull(result.speed)
    }

    @Test
    fun `at double speed the time halves and the speed is named`() {
        val result = left(listOf(item("a"), item("b")), speed = 2f)!!

        assertEquals(60.minutes, result.duration)
        assertEquals(2f, result.speed)
    }

    @Test
    fun `an item part-way through counts only what is left of it, by the state's length first`() {
        val states = mapOf("a" to PlayState.InProgress(positionMs = 20 * 60_000L, durationMs = 50 * 60_000L))

        val result = left(listOf(item("a", length = 90.minutes)), states = states)!!

        assertEquals(30.minutes, result.duration)
    }

    @Test
    fun `part-way with no length in the state uses the item's own length`() {
        val states = mapOf("a" to PlayState.InProgress(positionMs = 10 * 60_000L, durationMs = null))

        assertEquals(50.minutes, left(listOf(item("a", length = 60.minutes)), states = states)!!.duration)
    }

    @Test
    fun `an item already played is counted in full, as it would be if played again`() {
        val result = left(listOf(item("a")), states = mapOf("a" to PlayState.Played))!!

        assertEquals(60.minutes, result.duration)
    }

    @Test
    fun `an item with no known length makes the total at least, and is counted`() {
        val result = left(listOf(item("a"), item("mystery", length = null), item("other", length = null)))!!

        assertEquals(60.minutes, result.duration)
        assertEquals(2, result.unknown)
        assertTrue(result.atLeast)
    }

    @Test
    fun `a live stream is never counted in the time left but is counted as unknown`() {
        val result = left(listOf(item("a"), item("stream", length = 600.minutes, kind = MediaContentKind.LIVE)))!!

        assertEquals(60.minutes, result.duration)
        assertEquals(1, result.unknown)
    }

    @Test
    fun `music always plays at normal speed so the chosen speed neither shortens it nor is named`() {
        val music = left(listOf(item("song", length = 10.minutes, kind = MediaContentKind.MUSIC)), speed = 2f)!!

        assertEquals(10.minutes, music.duration)
        assertNull(music.speed)
    }

    @Test
    fun `a mix of music and talk speeds only the talk and names the speed`() {
        val mix = left(
            listOf(item("song", length = 10.minutes, kind = MediaContentKind.MUSIC), item("talk", length = 60.minutes)),
            speed = 2f,
        )!!

        assertEquals(40.minutes, mix.duration)
        assertEquals(2f, mix.speed)
    }

    @Test
    fun `an audiobook chapter keeps the chosen speed`() {
        val book = left(listOf(item("chapter", length = 60.minutes, kind = MediaContentKind.AUDIOBOOK)), speed = 1.5f)!!

        assertEquals(40.minutes, book.duration)
        assertEquals(1.5f, book.speed)
    }

    @Test
    fun `the total is rounded up to the whole minute`() {
        assertEquals(2.minutes, left(listOf(item("a", length = 61.seconds)))!!.duration)
        assertEquals(1.minutes, left(listOf(item("a", length = 60.seconds)))!!.duration)
        assertEquals(1.minutes, left(listOf(item("a", length = 1.seconds)))!!.duration)
    }

    @Test
    fun `nothing is said when nothing is known or nothing is left`() {
        assertNull(left(emptyList()))
        assertNull(left(listOf(item("a", length = null), item("b", length = null))))
        assertNull(
            left(
                listOf(item("a", length = 10.minutes)),
                states = mapOf("a" to PlayState.InProgress(600_000L, 600_000L))
            )
        )
        assertNull(left(listOf(item("stream", kind = MediaContentKind.LIVE))))
    }

    @Test
    fun `a position past the end counts as nothing left rather than a negative time`() {
        val states = mapOf("a" to PlayState.InProgress(positionMs = 99 * 60_000L, durationMs = 10 * 60_000L))

        assertNull(left(listOf(item("a")), states = states))
    }
}
