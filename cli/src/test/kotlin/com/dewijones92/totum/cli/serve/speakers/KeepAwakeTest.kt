package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.data.targets.KeepAwake
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class KeepAwakeTest {

    private class FakeTransport(var isLinked: Boolean = true, var reconnects: Boolean = true) : KeepAwakeTransport {
        var reconnectTries = 0
        val signals = mutableListOf<KeepAwakeSignal>()
        var signalWorks = true
        override suspend fun linked(): Boolean = isLinked
        override suspend fun reconnect(): Boolean {
            reconnectTries++
            if (reconnects) isLinked = true
            return reconnects
        }
        override suspend fun sendSignal(signal: KeepAwakeSignal): Boolean {
            signals += signal
            return signalWorks
        }
    }

    private val transport = FakeTransport()
    private var now = 1_000_000L
    private var time = LocalTime.of(12, 0)
    private fun keeper(window: KeepAwakeWindow = KeepAwakeWindow(), signal: KeepAwakeSignal = KeepAwakeSignal.SILENCE) =
        KeepAwakeKeeper(
            "mic",
            window,
            transport,
            signal,
            clock = { now },
            timeOfDay = { time },
            checkEveryMs = 30_000,
            signalEveryMs = 60_000,
            reconnectEveryMs = 120_000,
            reconnectMaxMs = 480_000,
            summaryEvery = 1_000,
        )

    @Test
    fun `a connected speaker with nothing playing is sent silence now and then, not on every check`() = runBlocking {
        val keeper = keeper()

        assertTrue(keeper.beat(busy = false))
        assertEquals(1, transport.signals.size)
        assertEquals(KeepAwake.KEPT, keeper.state)

        now += 30_000
        assertTrue(keeper.beat(busy = false))
        assertEquals("30 s later is not due", 1, transport.signals.size)

        now += 30_000
        keeper.beat(busy = false)
        assertEquals(2, transport.signals.size)
    }

    @Test
    fun `checks are no more often than their interval, however often it is asked`() = runBlocking {
        val keeper = keeper()
        assertTrue(keeper.beat(busy = false))

        now += 5_000
        assertFalse(keeper.beat(busy = false))
        now += 5_000
        assertFalse(keeper.beat(busy = false))
        assertEquals(1, transport.signals.size)
    }

    @Test
    fun `while something is playing nothing is sent, and it is still kept`() = runBlocking {
        val keeper = keeper()

        keeper.beat(busy = true)

        assertEquals(emptyList<KeepAwakeSignal>(), transport.signals)
        assertEquals(KeepAwake.KEPT, keeper.state)
    }

    @Test
    fun `a dropped connection is reopened, and silence follows straight away`() = runBlocking {
        transport.isLinked = false
        val keeper = keeper()

        keeper.beat(busy = false)

        assertEquals(1, transport.reconnectTries)
        assertEquals(1, transport.signals.size)
        assertEquals(KeepAwake.KEPT, keeper.state)
    }

    @Test
    fun `a speaker that is off is retried with a growing wait, capped, not hammered`() = runBlocking {
        transport.isLinked = false
        transport.reconnects = false
        val keeper = keeper()

        keeper.beat(busy = false)
        assertEquals(1, transport.reconnectTries)
        assertEquals(KeepAwake.RECONNECTING, keeper.state)

        repeat(7) {
            now += 30_000
            keeper.beat(busy = false)
        }
        assertEquals("210 s after the first try: still waiting out 240 s", 1, transport.reconnectTries)

        now += 30_000
        keeper.beat(busy = false)
        assertEquals(2, transport.reconnectTries)

        now += 479_000
        keeper.beat(busy = false)
        assertEquals("waits 480 s now, the cap", 2, transport.reconnectTries)
        now += 30_000
        keeper.beat(busy = false)
        assertEquals(3, transport.reconnectTries)
        assertEquals(emptyList<KeepAwakeSignal>(), transport.signals)
    }

    @Test
    fun `when it comes back the wait starts over`() = runBlocking {
        transport.isLinked = false
        transport.reconnects = false
        val keeper = keeper()
        keeper.beat(busy = false)
        now += 240_000
        keeper.beat(busy = false)
        assertEquals(2, transport.reconnectTries)

        transport.isLinked = true
        now += 30_000
        keeper.beat(busy = false)
        assertEquals(KeepAwake.KEPT, keeper.state)

        transport.isLinked = false
        now += 120_000
        keeper.beat(busy = false)
        assertEquals("120 s after the last try is due again; the 480 s wait is forgotten", 3, transport.reconnectTries)
    }

    @Test
    fun `outside its window nothing is sent and nothing is reopened`() = runBlocking {
        transport.isLinked = false
        val keeper = keeper(KeepAwakeWindow(LocalTime.of(7, 0), LocalTime.of(22, 0)))
        time = LocalTime.of(23, 30)

        keeper.beat(busy = false)

        assertEquals(KeepAwake.OUTSIDE_WINDOW, keeper.state)
        assertEquals(0, transport.reconnectTries)
        assertEquals(emptyList<KeepAwakeSignal>(), transport.signals)

        time = LocalTime.of(7, 30)
        now += 30_000
        keeper.beat(busy = false)
        assertEquals(KeepAwake.KEPT, keeper.state)
        assertEquals(1, transport.reconnectTries)
    }

    @Test
    fun `a window past midnight covers the night, and none at all covers the day`() {
        val night = KeepAwakeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0))
        assertTrue(night.covers(LocalTime.of(23, 0)))
        assertTrue(night.covers(LocalTime.of(5, 59)))
        assertFalse(night.covers(LocalTime.of(6, 0)))
        assertFalse(night.covers(LocalTime.of(12, 0)))
        assertTrue(KeepAwakeWindow().covers(LocalTime.of(3, 0)))
    }

    @Test
    fun `a signal that fails is counted and tried again at the next due time, not at once`() = runBlocking {
        transport.signalWorks = false
        val keeper = keeper()

        keeper.beat(busy = false)
        now += 30_000
        keeper.beat(busy = false)

        assertEquals(1, transport.signals.size)
        assertEquals(KeepAwake.KEPT, keeper.state)
    }

    @Test
    fun `the quiet hum is sent when that is the chosen signal`() = runBlocking {
        keeper(signal = KeepAwakeSignal.NOISE).beat(busy = false)

        assertEquals(listOf(KeepAwakeSignal.NOISE), transport.signals)
    }

    @Test
    fun `settings name speakers by name or id, each with an optional window, and everything else is off`() {
        val settings = KeepAwakeSettings.parse("Soundmic, 54158976cda4@07:00-22:30,Radio@nonsense", "noise")

        assertEquals(KeepAwakeWindow(), settings.windowFor("aabbccddeeff", "Soundmic"))
        assertEquals(
            KeepAwakeWindow(LocalTime.of(7, 0), LocalTime.of(22, 30)),
            settings.windowFor("54158976cda4", "LG soundbar"),
        )
        assertEquals("a bad window is on all day, loudly", KeepAwakeWindow(), settings.windowFor("x", "radio"))
        assertNull(settings.windowFor("x", "LG soundbar"))
        assertEquals(KeepAwakeSignal.NOISE, settings.signal)
        assertTrue(settings.any)
    }

    @Test
    fun `no setting means no speaker is kept awake, and an unknown signal is silence`() {
        assertFalse(KeepAwakeSettings.parse(null).any)
        assertFalse(KeepAwakeSettings.parse("  ").any)
        assertNull(KeepAwakeSettings.NONE.windowFor("a", "b"))
        assertEquals(KeepAwakeSignal.SILENCE, KeepAwakeSettings.parse("a", "tone").signal)
    }
}
