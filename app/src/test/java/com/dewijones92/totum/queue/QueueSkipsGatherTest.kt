package com.dewijones92.totum.queue

import com.dewijones92.totum.playback.SkipDirection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueSkipsGatherTest {

    @Test
    fun `back while gathering takes one press off instead of throwing them all away`() = runTest {
        val moves = mutableListOf<String>()
        val skips = QueueSkips(this, next = { moves += "next($it)" }, previous = { moves += "previous" })

        repeat(3) { skips.skip(SkipDirection.NEXT, "test") }
        skips.skip(SkipDirection.PREVIOUS, "test")
        advanceUntilIdle()

        assertEquals(listOf("next(2)"), moves)
    }

    @Test
    fun `back with nothing gathered steps back`() = runTest {
        val moves = mutableListOf<String>()
        val skips = QueueSkips(this, next = { moves += "next($it)" }, previous = { moves += "previous" })

        skips.skip(SkipDirection.PREVIOUS, "test")
        advanceUntilIdle()

        assertEquals(listOf("previous"), moves)
    }

    @Test
    fun `as many backs as nexts inside the gather leaves the queue where it was`() = runTest {
        val moves = mutableListOf<String>()
        val skips = QueueSkips(this, next = { moves += "next($it)" }, previous = { moves += "previous" })

        skips.skip(SkipDirection.NEXT, "test")
        skips.skip(SkipDirection.PREVIOUS, "test")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), moves)
    }
}
