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
        sleep = { ms -> slept += ms; clock += ms },
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
                    async(Dispatchers.Default) { gate.passing("c$n") { withContext(Dispatchers.IO) { Thread.sleep(5) } } }
                }.awaitAll()
            }
        }
        assertEquals(0, gate.inFlight)
    }
}
