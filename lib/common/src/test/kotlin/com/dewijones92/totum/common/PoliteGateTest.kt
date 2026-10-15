package com.dewijones92.totum.common

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
    fun `a 429 holds every call, doubling while YouTube keeps saying it`() {
        val gate = gate()
        gate.tooManyRequests("/player", retryAfterMs = null)
        assertEquals(30_000L, gate.slowDown.value!!.untilMs - clock)
        gate.tooManyRequests("/player", retryAfterMs = null)
        assertEquals(60_000L, gate.slowDown.value!!.untilMs - clock)
        gate.pass("next") {}
        assertEquals(60_000L, slept.first())
    }

    @Test
    fun `Retry-After wins when it asks for longer`() {
        val gate = gate()
        gate.tooManyRequests("/browse", retryAfterMs = 120_000)
        assertEquals(120_000L, gate.slowDown.value!!.untilMs - clock)
    }

    @Test
    fun `a success after the pause clears it and resets the doubling`() {
        val gate = gate()
        gate.tooManyRequests("/player", null)
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
}
