package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.ask.state.AskStateEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskStatesTest {

    private var now = 1_000_000L

    private fun envelope(extra: String = "") = AskStateEnvelope(
        JsonObject(emptyMap()),
        Json.parseToJsonElement(
            """{"snapshot":{"counts":{"queue":2}},""" +
                """"queue":{"items":[{"itemId":"a","title":"t$extra"},{"itemId":"b"}]},""" +
                """"settings":{"homeServerToken":"leak-me-please-1234"}}""",
        ) as JsonObject,
    )

    @Test
    fun `a snapshot is kept for ten minutes and then deleted`() {
        val states = AskStates(clock = { now })
        states.put("one-one-one", envelope())
        now += 9 * 60_000
        assertNotNull(states.get("one-one-one"))
        now += 61_000
        assertNull(states.get("one-one-one"))
        assertEquals(0, states.size)
    }

    @Test
    fun `the sweep deletes what has expired and reports how many`() {
        val states = AskStates(clock = { now })
        states.put("one-one-one", envelope())
        now += 5 * 60_000
        states.put("two-two-two", envelope())
        now += 6 * 60_000
        assertEquals(1, states.prune())
        assertNull(states.get("one-one-one"))
        assertNotNull(states.get("two-two-two"))
    }

    @Test
    fun `the oldest is dropped when more than the cap are held`() {
        val states = AskStates(max = 2, clock = { now })
        states.put("one-one-one", envelope())
        now += 1
        states.put("two-two-two", envelope())
        now += 1
        states.put("tri-tri-tri", envelope())
        assertNull(states.get("one-one-one"))
        assertEquals(2, states.size)
    }

    @Test
    fun `a snapshot is scrubbed again on arrival and its item ids are indexed`() {
        val states = AskStates(clock = { now })
        val stored = states.put("one-one-one", envelope())
        assertFalse(stored.state.contains("leak-me-please"))
        assertFalse(stored.state.contains("homeServerToken"))
        assertEquals(setOf("a", "b"), stored.itemIds)
        assertTrue(stored.counts.contains("queue"))
    }

    @Test
    fun `forgetting removes it at once`() {
        val states = AskStates(clock = { now })
        states.put("one-one-one", envelope())
        states.forget("one-one-one")
        assertNull(states.get("one-one-one"))
    }
}
