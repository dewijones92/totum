package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class CarryOnTest {

    private fun played(
        id: String,
        length: Duration? = 60.minutes,
        kind: MediaContentKind = MediaContentKind.STANDARD,
    ) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = id,
            publishedAt = null,
            duration = length,
            mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
            contentKind = kind,
        ),
        PlayHandle.Podcast(),
    )

    private fun half(durationMs: Long? = 3_600_000L) = PlayState.InProgress(600_000L, durationMs)

    private fun carry(history: List<PlayableItem>, limit: Int = CARRY_ON_LIMIT, states: Map<String, PlayState>) =
        carryOn(history, limit) { states[it.value] ?: PlayState.Unplayed }.map { it.playable.item.id.value }

    @Test
    fun `part-played items come back in history order, most recent first`() {
        val history = listOf(played("c"), played("a"), played("b"))

        assertEquals(
            listOf("c", "a", "b"),
            carry(history, states = mapOf("a" to half(), "b" to half(), "c" to half())),
        )
    }

    @Test
    fun `unplayed, played and unknown items are left out`() {
        val history = listOf(played("unplayed"), played("done"), played("halfway"), played("unknown"))

        assertEquals(
            listOf("halfway"),
            carry(
                history,
                states = mapOf("done" to PlayState.Played, "halfway" to half(), "unplayed" to PlayState.Unplayed)
            ),
        )
    }

    @Test
    fun `an item inside the finished tail is left out by the state's own length`() {
        val long = played("long", length = null)
        val nearEnd = PlayState.InProgress(positionMs = 3_600_000L - 14_999L, durationMs = 3_600_000L)
        val justBefore = PlayState.InProgress(positionMs = 3_600_000L - 15_001L, durationMs = 3_600_000L)

        assertEquals(emptyList<String>(), carry(listOf(long), states = mapOf("long" to nearEnd)))
        assertEquals(listOf("long"), carry(listOf(long), states = mapOf("long" to justBefore)))
    }

    @Test
    fun `a short item is nearly finished by its last tenth, not its last fifteen seconds`() {
        val short = played("short", length = null)

        assertEquals(
            emptyList<String>(),
            carry(listOf(short), states = mapOf("short" to PlayState.InProgress(91_000L, 100_000L))),
        )
        assertEquals(
            listOf("short"),
            carry(listOf(short), states = mapOf("short" to PlayState.InProgress(89_000L, 100_000L))),
        )
    }

    @Test
    fun `when the state has no length the item's own length decides`() {
        val episode = played("episode", length = 10.minutes)
        val nearEnd = PlayState.InProgress(positionMs = 590_000L, durationMs = null)
        val early = PlayState.InProgress(positionMs = 300_000L, durationMs = null)

        assertEquals(emptyList<String>(), carry(listOf(episode), states = mapOf("episode" to nearEnd)))
        assertEquals(listOf("episode"), carry(listOf(episode), states = mapOf("episode" to early)))
    }

    @Test
    fun `with no length anywhere an item cannot be called nearly finished`() {
        val unknown = played("unknown", length = null)

        assertEquals(
            listOf("unknown"),
            carry(listOf(unknown), states = mapOf("unknown" to PlayState.InProgress(65_000L, null))),
        )
    }

    @Test
    fun `an item under a minute in was never really started and is left out`() {
        val started = played("started")

        assertEquals(
            emptyList<String>(),
            carry(listOf(started), states = mapOf("started" to PlayState.InProgress(59_999L, 3_600_000L))),
        )
        assertEquals(
            listOf("started"),
            carry(listOf(started), states = mapOf("started" to PlayState.InProgress(60_000L, 3_600_000L))),
        )
    }

    @Test
    fun `music and live streams are left out however far in they are`() {
        val history = listOf(
            played("song", kind = MediaContentKind.MUSIC),
            played("stream", kind = MediaContentKind.LIVE),
            played("wasLive", kind = MediaContentKind.WAS_LIVE),
            played("talk"),
        )
        val states = history.associate { it.item.id.value to half() }

        assertEquals(listOf("wasLive", "talk"), carry(history, states = states))
    }

    @Test
    fun `the row stops at the limit and the most recent are the ones kept`() {
        val history = (1..20).map { played("item$it") }
        val states = history.associate { it.item.id.value to half() }

        val shown = carry(history, states = states)

        assertEquals(CARRY_ON_LIMIT, shown.size)
        assertEquals("item1", shown.first())
        assertEquals("item$CARRY_ON_LIMIT", shown.last())
        assertEquals(listOf("item1", "item2"), carry(history, limit = 2, states = states))
    }

    @Test
    fun `each entry carries the state it was judged by so the card can draw its bar`() {
        val state = PlayState.InProgress(1_200_000L, 3_600_000L)

        val entry = carryOn(listOf(played("a"))) { state }.single()

        assertEquals(state, entry.progress)
        assertTrue(entry.playable.item.id == MediaItemId("a"))
    }

    @Test
    fun `nothing in the history means nothing to carry on`() {
        assertEquals(emptyList<String>(), carry(emptyList(), states = emptyMap()))
    }
}
