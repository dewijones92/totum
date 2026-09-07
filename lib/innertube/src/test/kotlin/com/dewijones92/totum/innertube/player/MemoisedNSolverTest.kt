package com.dewijones92.totum.innertube.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoisedNSolverTest {

    private val calls = mutableListOf<List<String>>()
    private val slow = NSolver { challenges, _ ->
        calls += challenges
        challenges.associateWith { "solved-$it" }
    }

    @Test
    fun `the same challenges for the same player are solved once`() = runBlocking {
        val solver = MemoisedNSolver(slow)
        assertEquals(mapOf("a" to "solved-a", "b" to "solved-b"), solver.solve(listOf("a", "b"), "p1"))
        assertEquals(mapOf("a" to "solved-a", "b" to "solved-b"), solver.solve(listOf("a", "b"), "p1"))
        assertEquals(1, calls.size)
    }

    @Test
    fun `only the unknown challenges go to the delegate`() = runBlocking {
        val solver = MemoisedNSolver(slow)
        solver.solve(listOf("a"), "p1")
        assertEquals(mapOf("a" to "solved-a", "c" to "solved-c"), solver.solve(listOf("a", "c"), "p1"))
        assertEquals(listOf(listOf("a"), listOf("c")), calls)
    }

    @Test
    fun `a new player build is a new question`() = runBlocking {
        val solver = MemoisedNSolver(slow)
        solver.solve(listOf("a"), "p1")
        solver.solve(listOf("a"), "p2")
        assertEquals(2, calls.size)
    }

    @Test
    fun `an unsolvable challenge is not remembered as solved`() = runBlocking {
        val failing = NSolver { challenges, _ ->
            calls += challenges
            emptyMap()
        }
        val solver = MemoisedNSolver(failing)
        assertEquals(emptyMap<String, String>(), solver.solve(listOf("a"), "p1"))
        solver.solve(listOf("a"), "p1")
        assertEquals("asked again, not remembered as failed", 2, calls.size)
    }

    @Test
    fun `the memory is bounded`() = runBlocking {
        val solver = MemoisedNSolver(slow, capacity = 2)
        solver.solve(listOf("a", "b", "c"), "p1")
        solver.solve(listOf("a"), "p1")
        assertEquals("a was evicted, so it is solved again", 2, calls.size)
    }
}
