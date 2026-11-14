package com.dewijones92.totum.mobiledata

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class MobileDataMeterTest {

    private val mb = BYTES_PER_MB
    private val monday = LocalDate.of(2026, 11, 9)
    private val tuesday = LocalDate.of(2026, 11, 10)

    private var counter: Long? = 1_000 * mb
    private var onMobile = true
    private var limit = 1_000
    private var day = monday
    private val store = InMemoryMobileDataStore()

    private fun TestScope.meter(every: Long = 5_000) = MobileDataMeter(
        store = store,
        scope = backgroundScope,
        uidBytes = { counter },
        metered = { onMobile },
        limitMb = { limit },
        today = { day },
        sampleEveryMs = every,
    )

    @Test
    fun `the first reading only sets the baseline and counts nothing`() = runTest {
        val meter = meter()

        meter.sample()

        assertEquals(0L, meter.state.value.bytes)
        assertEquals(monday, meter.state.value.day)
    }

    @Test
    fun `bytes used on a metered connection are added to today's total`() = runTest {
        val meter = meter()
        meter.sample()

        counter = counter!! + 5 * mb
        meter.sample()
        counter = counter!! + 2 * mb
        meter.sample()

        assertEquals(7 * mb, meter.state.value.bytes)
        assertTrue(meter.state.value.metered)
    }

    @Test
    fun `bytes used on wifi are not counted, and do not count later when mobile data comes back`() = runTest {
        val meter = meter()
        meter.sample()

        onMobile = false
        counter = counter!! + 300 * mb
        meter.sample()
        onMobile = true
        counter = counter!! + 1 * mb
        meter.sample()

        assertEquals(1 * mb, meter.state.value.bytes)
    }

    @Test
    fun `the state says whether the connection is metered right now`() = runTest {
        val meter = meter()
        meter.sample()
        onMobile = false
        meter.sample()

        assertFalse(meter.state.value.metered)
    }

    @Test
    fun `a counter that drops after a reboot counts what it shows as new traffic`() = runTest {
        val meter = meter()
        meter.sample()
        counter = counter!! + 10 * mb
        meter.sample()

        counter = 3 * mb
        meter.sample()
        counter = counter!! + 4 * mb
        meter.sample()

        assertEquals(10 * mb + 3 * mb + 4 * mb, meter.state.value.bytes)
    }

    @Test
    fun `at local midnight the total starts again, with the new day's own traffic in it`() = runTest {
        val meter = meter()
        meter.sample()
        counter = counter!! + 800 * mb
        meter.sample()
        assertEquals(800 * mb, meter.state.value.bytes)

        day = tuesday
        counter = counter!! + 5 * mb
        meter.sample()

        assertEquals(tuesday, meter.state.value.day)
        assertEquals(5 * mb, meter.state.value.bytes)
    }

    @Test
    fun `a phone that reports no byte counters counts nothing, changes nothing and saves nothing`() = runTest {
        val meter = meter()
        meter.sample()
        counter = counter!! + 5 * mb
        meter.sample()
        val before = meter.state.value
        val saved = store.load()

        counter = null
        meter.sample()
        meter.sample()

        assertEquals(before, meter.state.value)
        assertEquals(saved, store.load())
    }

    @Test
    fun `the counter coming back after a gap counts only what moved since the last reading`() = runTest {
        val meter = meter()
        meter.sample()
        counter = counter!! + 5 * mb
        meter.sample()
        val last = counter!!

        counter = null
        meter.sample()
        counter = last + 2 * mb
        meter.sample()

        assertEquals(7 * mb, meter.state.value.bytes)
    }

    @Test
    fun `a restart carries on from the total and the counter that were saved`() = runTest {
        val first = meter()
        first.sample()
        counter = counter!! + 40 * mb
        first.sample()

        counter = counter!! + 6 * mb
        val restarted = meter()
        assertEquals(40 * mb, restarted.state.value.bytes)
        restarted.sample()

        assertEquals(46 * mb, restarted.state.value.bytes)
    }

    @Test
    fun `a total saved yesterday is yesterday's until the first reading today, and then resets`() = runTest {
        val first = meter()
        first.sample()
        counter = counter!! + 90 * mb
        first.sample()
        day = tuesday

        val restarted = meter()
        assertEquals(monday, restarted.state.value.day)
        restarted.sample()

        assertEquals(tuesday, restarted.state.value.day)
        assertEquals(0L, restarted.state.value.bytes)
    }

    @Test
    fun `every reading is saved with its day, total and counter`() = runTest {
        val meter = meter()
        meter.sample()
        counter = counter!! + 3 * mb

        meter.sample()

        assertEquals(MobileDataSaved(monday, 3 * mb, counter), store.load())
    }

    @Test
    fun `started, it reads straight away and then every interval`() = runTest {
        val meter = meter(every = 5_000)
        meter.start()
        runCurrent()
        assertEquals(0L, meter.state.value.bytes)

        counter = counter!! + 1 * mb
        advanceTimeBy(5_001)
        assertEquals(1 * mb, meter.state.value.bytes)

        counter = counter!! + 1 * mb
        advanceTimeBy(5_000)
        assertEquals(2 * mb, meter.state.value.bytes)
    }

    @Test
    fun `twenty thousand samples of a kilobyte add up exactly`() = runTest {
        val meter = meter()
        meter.sample()

        repeat(20_000) {
            counter = counter!! + 1_024
            meter.sample()
        }

        assertEquals(20_000L * 1_024, meter.state.value.bytes)
    }

    @Test
    fun `the limit is reached exactly at the limit, and off means never`() {
        val limitBytes = 500 * mb
        val under = MobileDataState(monday, limitBytes - 1, metered = true)
        val at = MobileDataState(monday, limitBytes, metered = true)

        assertFalse(under.reachedLimit(500))
        assertTrue(at.reachedLimit(500))
        assertFalse(at.reachedLimit(0))
        assertFalse(at.reachedLimit(-1))
    }

    @Test
    fun `only a metered connection past the limit holds video back`() {
        val past = MobileDataState(monday, 2_000 * mb, metered = true)

        assertTrue(past.holdingBack(1_000))
        assertFalse(past.copy(metered = false).holdingBack(1_000))
        assertFalse(past.holdingBack(0))
        assertFalse(past.copy(bytes = 10 * mb).holdingBack(1_000))
    }

    @Test
    fun `a saved total with no day yet starts on today`() = runTest {
        val meter = meter()

        assertEquals(monday, meter.state.value.day)
        assertNull(store.load().day)
    }
}
