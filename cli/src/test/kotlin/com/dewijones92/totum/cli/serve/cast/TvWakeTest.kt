package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.data.targets.TargetPower
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvWakeTest {

    private val mac = byteArrayOf(0x20, 0x28, 0xbc.toByte(), 0x6b, 0xf7.toByte(), 0xf8.toByte())
    private val broadcasts = listOf("255.255.255.255", "192.168.0.255")

    private class Sent(val address: String, val port: Int, val packet: ByteArray)

    private val sent = mutableListOf<Sent>()
    private var now = 0L
    private var probes = 0
    private val seenWhileProbing = mutableListOf<TargetPower>()
    private var answersFromProbe = Int.MAX_VALUE
    private var throwOn: String? = null
    private var onProbe: suspend () -> Unit = {}

    private lateinit var wake: TvWake

    private fun build(answersFromProbe: Int = Int.MAX_VALUE): TvWake {
        this.answersFromProbe = answersFromProbe
        return TvWake(
            label = "LG TV",
            mac = mac,
            broadcasts = broadcasts,
            sender = { packet, address, port ->
                if (address == throwOn) error("network unreachable")
                sent += Sent(address, port, packet)
            },
            answers = {
                seenWhileProbing += wake.power
                onProbe()
                probes++ >= this.answersFromProbe
            },
            pause = {
                now += it
                yield()
            },
            clock = { now },
        ).also { wake = it }
    }

    @Test
    fun `a TV that already answers is not woken, and is on`() = runBlocking {
        build(answersFromProbe = 0)

        assertTrue(wake.ensureAwake())

        assertTrue(sent.isEmpty())
        assertEquals(TargetPower.ON, wake.power)
        assertEquals(0L, now)
    }

    @Test
    fun `a sleeping TV is sent the magic packet to every broadcast on both ports, then waited for until it answers`() =
        runBlocking {
            build(answersFromProbe = 4)

            assertTrue(wake.ensureAwake())

            assertEquals(
                listOf("255.255.255.255" to 9, "255.255.255.255" to 7, "192.168.0.255" to 9, "192.168.0.255" to 7),
                sent.map { it.address to it.port },
            )
            assertTrue(sent.all { it.packet.contentEquals(WakeOnLan.magicPacket(mac)) })
            assertEquals(TargetPower.ON, wake.power)
            assertNull(wake.failure)
            assertTrue("answered 3 polls after the first check: $now ms", now in 1..10_000)
        }

    @Test
    fun `while it waits the TV is waking, so the phone can say so`() = runBlocking {
        build(answersFromProbe = 3)

        wake.ensureAwake()

        assertEquals(TargetPower.ON, seenWhileProbing.first())
        assertTrue(seenWhileProbing.drop(1).all { it == TargetPower.WAKING })
    }

    @Test
    fun `a TV that never answers fails after 25 seconds and says what to check`() = runBlocking {
        build()

        assertFalse(wake.ensureAwake())

        assertEquals(TargetPower.ASLEEP, wake.power)
        assertEquals("❌ LG TV didn't wake: check 'Turn on via Wi-Fi' in its settings", wake.failure)
        assertTrue("gave up at $now ms", now in 25_000..26_000)
    }

    @Test
    fun `the magic packet is sent again every five seconds, since UDP can be lost`() = runBlocking {
        build(answersFromProbe = 12)

        wake.ensureAwake()

        val rounds = sent.size / (broadcasts.size * 2)
        assertEquals("sent at 0, 5 and 10 seconds", 3, rounds)
    }

    @Test
    fun `a broadcast that cannot be sent to does not stop the others`() = runBlocking {
        throwOn = "255.255.255.255"
        build(answersFromProbe = 2)

        assertTrue(wake.ensureAwake())

        assertEquals(setOf("192.168.0.255"), sent.map { it.address }.toSet())
    }

    @Test
    fun `a later wake clears the last failure`() = runBlocking {
        build()
        wake.ensureAwake()
        assertTrue(wake.failure != null)
        probes = 0
        answersFromProbe = 2

        assertTrue(wake.ensureAwake())

        assertNull(wake.failure)
    }

    @Test
    fun `a refresh reads whether the TV answers`() = runBlocking {
        build(answersFromProbe = 1)
        wake.refresh()
        assertEquals(TargetPower.ASLEEP, wake.power)

        wake.refresh()
        assertEquals(TargetPower.ON, wake.power)
    }

    @Test
    fun `a refresh leaves a wake in progress alone`() = runBlocking {
        build(answersFromProbe = 3)
        var checked = false
        onProbe = {
            if (wake.power == TargetPower.WAKING && !checked) {
                checked = true
                val before = probes
                wake.refresh()
                assertEquals("the refresh did not probe", before, probes)
                assertEquals(TargetPower.WAKING, wake.power)
            }
        }

        wake.ensureAwake()

        assertTrue(checked)
    }

    @Test
    fun `two requests to a sleeping TV send one wake between them`() = runBlocking {
        build(answersFromProbe = 3)

        val first = async { wake.ensureAwake() }
        val second = async {
            yield()
            wake.ensureAwake()
        }

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(broadcasts.size * 2, sent.size)
    }
}
