package com.dewijones92.totum.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PoliteGateTest {

    private var clock = 0L
    private val slept = mutableListOf<Long>()
    private fun gate(perSecond: Double = 2.0, burst: Int = 5) = PoliteGate(
        maxInFlight = 4,
        perSecond = perSecond,
        burst = burst,
        now = { clock },
        sleep = { ms ->
            slept += ms
            clock += ms
        },
        jitter = { 0.5 },
    )

    @Test
    fun `a burst passes at once and the rest trickle at the steady rate`() {
        val gate = gate(perSecond = 2.0, burst = 5)
        repeat(9) { gate.pass("call $it") {} }
        assertEquals(2000L, slept.sum())
    }

    @Test
    fun `a 429 holds every call, and one burst of 429s escalates only once`() {
        val gate = gate()
        gate.tooManyRequests("/player", retryAfterMs = null)
        gate.tooManyRequests("/next", retryAfterMs = null)
        assertEquals(30_000L, gate.slowDown.value!!.untilMs - clock)
        gate.pass("next") {}
        assertEquals(30_000L, slept.first())
        gate.tooManyRequests("/player", retryAfterMs = null)
        assertEquals(60_000L, gate.slowDown.value!!.untilMs - clock)
    }

    @Test
    fun `Retry-After wins when longer, and a later shorter answer never shrinks the pause`() {
        val gate = gate()
        gate.tooManyRequests("/browse", retryAfterMs = 600_000)
        gate.tooManyRequests("/browse", retryAfterMs = null)
        assertEquals(600_000L, gate.slowDown.value!!.untilMs - clock)
    }

    @Test
    fun `a success after the pause clears it and resets the doubling`() {
        val gate = gate()
        gate.tooManyRequests("/player", null)
        clock += 31_000
        gate.tooManyRequests("/player", null)
        clock += 61_000
        gate.served()
        assertNull(gate.slowDown.value)
        gate.tooManyRequests("/player", null)
        assertEquals(30_000L, gate.slowDown.value!!.untilMs - clock)
    }

    @Test
    fun `never more than four calls in flight`() {
        val gate = PoliteGate(maxInFlight = 4, perSecond = 1000.0, burst = 1000)
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val done = CountDownLatch(16)
        val pool = Executors.newFixedThreadPool(16)
        repeat(16) {
            pool.execute {
                gate.pass("c$it") {
                    most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    Thread.sleep(20)
                    inFlight.decrementAndGet()
                }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(4, most.get())
    }

    @Test
    fun `a call kept waiting for a slot says at once which calls hold them`() = runTest {
        val lines = mutableListOf<String>()
        val before = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "polite") lines += message }
        try {
            val gate =
                PoliteGate(maxInFlight = 1, perSecond = 1000.0, burst = 1000, now = { testScheduler.currentTime })
            val holder = launch { gate.passing("player SLOWHOLDER") { kotlinx.coroutines.delay(10_000) } }
            advanceTimeBy(10)
            val waiter = launch { gate.passing("next WAITER") {} }
            advanceTimeBy(3_000)

            val slow = lines.singleOrNull { "next WAITER" in it && "player SLOWHOLDER" in it }
            assertTrue("no slow-slot line naming the holder in $lines", slow != null)
            holder.cancel()
            waiter.join()
        } finally {
            Diag.sink = before
        }
    }

    @Test
    fun `a tap waiting for a slot goes ahead of background calls that queued first`() = runTest {
        val gate = PoliteGate(maxInFlight = 1, perSecond = 1000.0, burst = 1000, now = { testScheduler.currentTime })
        val order = mutableListOf<String>()
        val holder = launch { gate.passing("holder") { kotlinx.coroutines.delay(1_000) } }
        advanceTimeBy(10)
        val background = launch { gate.passing("prefetch") { order += "prefetch" } }
        advanceTimeBy(10)
        val tap = launch(PoliteGate.interactive()) { gate.passing("play") { order += "play" } }
        advanceTimeBy(5_000)

        holder.join()
        background.join()
        tap.join()
        assertEquals(listOf("play", "prefetch"), order)
    }

    @Test
    fun `blocking callers get their slots in the order they arrived`() {
        val gate = PoliteGate(maxInFlight = 1, perSecond = 10_000.0, burst = 10_000)
        val pool = Executors.newFixedThreadPool(9)
        val release = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        pool.submit { gate.pass("holder") { release.await() } }
        Thread.sleep(50)
        val waiters = (1..8).map { n ->
            pool.submit {
                gate.pass("call $n") { order += n }
            }.also { Thread.sleep(40) }
        }
        release.countDown()
        waiters.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals((1..8).toList(), order)
    }

    @Test
    fun `a call cancelled while it waits gives its slot back`() = runTest {
        val gate = PoliteGate(maxInFlight = 1, perSecond = 0.001, burst = 1, now = { testScheduler.currentTime })
        gate.passing("drains the bucket") {}
        val waiting = launch { gate.passing("waits for a token") {} }
        advanceTimeBy(50)
        assertEquals(1, gate.inFlight)
        waiting.cancel()
        waiting.join()
        assertEquals(0, gate.inFlight)
    }

    @Test
    fun `a hundred callers that each need an IO thread all finish`() = runBlocking {
        val gate = PoliteGate(maxInFlight = 4, perSecond = 10_000.0, burst = 10_000)
        withTimeout(20_000) {
            coroutineScope {
                (1..100).map { n ->
                    async(
                        Dispatchers.Default
                    ) { gate.passing("c$n") { withContext(Dispatchers.IO) { Thread.sleep(5) } } }
                }.awaitAll()
            }
        }
        assertEquals(0, gate.inFlight)
    }
}
