package com.dewijones92.totum.operations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationsTest {

    private val operations = Operations(clock = { 0L })

    @Test
    fun `a running operation shows its progress and leaves with its result`() = runTest {
        val ended = mutableListOf<Ended>()
        val collector = launch { operations.ended.collect { ended += it } }
        val proceed = CompletableDeferred<Unit>()
        launch {
            operations.track("k", OperationKind.SHUFFLE_ARTIST, "Chopin") { progress ->
                progress.report(found = 150, step = 3, steps = 201, playing = true)
                proceed.await()
                OperationEnd.Done(3619)
            }
        }
        advanceUntilIdle()

        val live = operations.running.value.single()
        assertEquals(listOf(150, 3, 201), listOf(live.found, live.step, live.steps))
        assertTrue(live.playing)
        assertTrue(operations.isRunning("k"))

        proceed.complete(Unit)
        advanceUntilIdle()

        assertTrue(operations.running.value.isEmpty())
        assertEquals(Ended(OperationKind.SHUFFLE_ARTIST, "Chopin", OperationEnd.Done(3619), 0), ended.single())
        collector.cancel()
    }

    @Test
    fun `work shown on the strip is listed while it runs and hands back its answer`() = runTest {
        val proceed = CompletableDeferred<Unit>()
        var answer: String? = null
        launch {
            answer = operations.showing("restore", "Restoring a backup") {
                proceed.await()
                "12 restored"
            }
        }
        advanceUntilIdle()

        assertEquals(
            listOf(OperationKind.TASK to "Restoring a backup"),
            operations.running.value.map { it.kind to it.subject }
        )
        proceed.complete(Unit)
        advanceUntilIdle()

        assertEquals("12 restored", answer)
        assertTrue(operations.running.value.isEmpty())
    }

    @Test
    fun `work shown on the strip that fails leaves the strip and rethrows`() = runTest {
        val ended = mutableListOf<Ended>()
        val collector = launch { operations.ended.collect { ended += it } }
        advanceUntilIdle()
        val thrown = runCatching { operations.showing("x", "Deleting 3 downloads") { error("disk full") } }

        assertEquals("disk full", thrown.exceptionOrNull()?.message)
        advanceUntilIdle()
        assertEquals(OperationEnd.Failed("disk full"), ended.single().end)
        assertTrue(operations.running.value.isEmpty())
        collector.cancel()
    }

    @Test
    fun `cancel stops the work and says whether anything was already playing`() = runTest {
        val ended = mutableListOf<Ended>()
        val collector = launch { operations.ended.collect { ended += it } }
        launch {
            operations.track("k", OperationKind.SHUFFLE_SOURCES, "Peep Show") { progress ->
                progress.report(found = 40)
                CompletableDeferred<Unit>().await()
                OperationEnd.Done(40)
            }
        }
        advanceUntilIdle()

        operations.cancel(operations.running.value.single().id)
        advanceUntilIdle()

        assertTrue(operations.running.value.isEmpty())
        assertEquals(OperationEnd.Cancelled(playing = false), ended.single().end)
        collector.cancel()
    }

    @Test
    fun `a failure is reported, never silent`() = runTest {
        val ended = mutableListOf<Ended>()
        val collector = launch { operations.ended.collect { ended += it } }
        advanceUntilIdle()

        val end = operations.track("k", OperationKind.SHUFFLE_ARTIST, "Chopin") { error("artist page gone") }
        advanceUntilIdle()

        assertEquals(OperationEnd.Failed("artist page gone"), end)
        assertEquals(end, ended.single().end)
        collector.cancel()
    }

    @Test
    fun `even an Error leaves the strip, and is reported`() = runTest {
        val ended = mutableListOf<Ended>()
        val collector = launch { operations.ended.collect { ended += it } }
        advanceUntilIdle()

        val thrown = runCatching {
            operations.track("k", OperationKind.SHUFFLE_ARTIST, "Chopin") { throw NotImplementedError("not yet") }
        }.exceptionOrNull()
        advanceUntilIdle()

        assertTrue(thrown is NotImplementedError)
        assertTrue(operations.running.value.isEmpty())
        assertTrue(ended.single().end is OperationEnd.Failed)
        collector.cancel()
    }
}
