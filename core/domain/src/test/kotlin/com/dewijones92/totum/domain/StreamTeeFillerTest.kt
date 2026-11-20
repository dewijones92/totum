package com.dewijones92.totum.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTeeFillerTest {

    private class FakeStream(val length: Long) {
        val held = mutableListOf<ByteRange>()
        var playerNetworkBytes = 0L
        var teeNetworkBytes = 0L
        var refetchedBytes = 0L
        var positionMs = 0L
        var playing = true
        val durationMs = 3_600_000L

        fun heldBytesIn(range: ByteRange): Long = held.sumOf { h ->
            maxOf(0L, minOf(h.endExclusive, range.endExclusive) - maxOf(h.start, range.start))
        }

        fun write(range: ByteRange, byTee: Boolean) {
            val already = heldBytesIn(range)
            refetchedBytes += already
            if (byTee) teeNetworkBytes += range.length else playerNetworkBytes += range.length
            held += range
            val merged = held.sortedBy { it.start }.fold(mutableListOf<ByteRange>()) { acc, r ->
                val last = acc.lastOrNull()
                if (last != null && r.start <= last.endExclusive) {
                    acc[acc.lastIndex] = ByteRange(last.start, maxOf(last.endExclusive, r.endExclusive))
                } else {
                    acc += r
                }
                acc
            }
            held.clear()
            held += merged
        }

        @Suppress("LoopWithTooManyJumpStatements")
        fun playerReadsAhead(bytes: Long, buffer: Long) {
            val playhead = (length.toDouble() * positionMs / durationMs).toLong()
            val want = ByteRange(playhead.coerceAtMost(length), minOf(length, playhead + buffer))
            var cursor = want.start
            for (h in held.sortedBy { it.start }) {
                if (h.endExclusive <= cursor) continue
                if (h.start > cursor) {
                    val miss = ByteRange(cursor, minOf(h.start, want.endExclusive))
                    if (miss.length > 0) write(miss, byTee = false)
                }
                cursor = maxOf(cursor, h.endExclusive)
                if (cursor >= want.endExclusive) break
            }
            if (cursor < want.endExclusive) write(ByteRange(cursor, want.endExclusive), byTee = false)
            positionMs = (positionMs + bytes * durationMs / length).coerceAtMost(durationMs)
        }
    }

    private fun filler(
        stream: FakeStream,
        plan: StreamTeePlan,
        onWait: () -> Unit,
        stopAfterWaits: Int = MAX_WAITS,
    ): StreamTeeFiller {
        var waits = 0
        return StreamTeeFiller(
            plan = plan,
            held = { stream.held.toList() },
            spot = { PlayerSpot(stream.positionMs, stream.durationMs, stream.playing) },
            fetch = { range ->
                stream.write(range, byTee = true)
                stream.playerReadsAhead(FETCH_STEP, PLAYER_BUFFER)
            },
            wait = {
                if (++waits > stopAfterWaits) error("the filler waited more than $stopAfterWaits times")
                onWait()
                stream.playerReadsAhead(WAIT_STEP, PLAYER_BUFFER)
            },
        )
    }

    @Test
    fun `a stream played from the start is fetched once between the player and the tee`() = runBlocking {
        val stream = FakeStream(LENGTH)
        stream.playerReadsAhead(0, PLAYER_BUFFER)

        val outcome = filler(stream, StreamTeePlan(LENGTH), onWait = {}).run()

        assertTrue(outcome.complete)
        val total = stream.playerNetworkBytes + stream.teeNetworkBytes
        assertEquals("no byte is fetched twice", 0L, stream.refetchedBytes)
        assertEquals("the whole file and nothing more crossed the network", LENGTH, total)
        assertTrue("the tee did real work: ${stream.teeNetworkBytes}", stream.teeNetworkBytes > LENGTH / 2)
    }

    @Test
    fun `a stream resumed in the middle is also fetched once`() = runBlocking {
        val stream = FakeStream(LENGTH)
        stream.positionMs = stream.durationMs / 2
        stream.playerReadsAhead(0, PLAYER_BUFFER)

        val outcome = filler(stream, StreamTeePlan(LENGTH), onWait = {}).run()

        assertTrue(outcome.complete)
        assertEquals(0L, stream.refetchedBytes)
        assertEquals(LENGTH, stream.playerNetworkBytes + stream.teeNetworkBytes)
    }

    @Test
    fun `a pause lets the tee fetch the rest without touching what the player has`() = runBlocking {
        val stream = FakeStream(LENGTH)
        stream.playerReadsAhead(0, PLAYER_BUFFER)
        stream.playing = false

        val outcome = filler(stream, StreamTeePlan(LENGTH), onWait = {}).run()

        assertTrue(outcome.complete)
        assertEquals(0L, stream.refetchedBytes)
        assertEquals(LENGTH, stream.playerNetworkBytes + stream.teeNetworkBytes)
    }

    @Test
    fun `while the player plays, the tee never writes inside the margin around the playhead`() {
        val plan = StreamTeePlan(LENGTH)
        val held = listOf(ByteRange(0, PLAYER_BUFFER))
        val spot = PlayerSpot(positionMs = 0, durationMs = 3_600_000, playing = true)

        val chunk = plan.nextChunk(held, spot)!!

        assertEquals("far end first", LENGTH, chunk.endExclusive)
        assertTrue(chunk.start >= plan.playheadBytes(spot)!! + plan.marginBytes())
    }

    @Test
    fun `with only the margin left the tee waits instead of fetching`() {
        val plan = StreamTeePlan(LENGTH)
        val playhead = LENGTH / 2
        val spot = PlayerSpot(1_800_000, 3_600_000, playing = true)
        val unsafe = ByteRange(playhead - plan.marginBytes(), playhead + plan.marginBytes())
        val held = listOf(ByteRange(0, unsafe.start), ByteRange(unsafe.endExclusive, LENGTH))

        assertNull(plan.nextChunk(held, spot))
    }

    @Test
    fun `while the player plays an item of unknown length the tee waits rather than guess`() {
        val plan = StreamTeePlan(LENGTH)

        assertNull(plan.nextChunk(listOf(ByteRange(0, PLAYER_BUFFER)), PlayerSpot(1_000, null, playing = true)))
        assertEquals(
            LENGTH,
            plan.nextChunk(listOf(ByteRange(0, PLAYER_BUFFER)), PlayerSpot(1_000, null, playing = false))!!.endExclusive
        )
    }

    @Test
    fun `holes are the complement of what is held`() {
        val plan = StreamTeePlan(1_000)

        assertEquals(
            listOf(ByteRange(0, 100), ByteRange(300, 600), ByteRange(900, 1_000)),
            plan.holes(listOf(ByteRange(100, 300), ByteRange(600, 900))),
        )
        assertEquals(emptyList<ByteRange>(), plan.holes(listOf(ByteRange(0, 1_000))))
        assertEquals(listOf(ByteRange(0, 1_000)), plan.holes(emptyList()))
    }

    private companion object {
        const val LENGTH = 70L * 1024 * 1024
        const val MAX_WAITS = 1_000
        const val FETCH_STEP = 16L * 1024
        const val WAIT_STEP = 512L * 1024
        const val PLAYER_BUFFER = 1L * 1024 * 1024
    }
}
