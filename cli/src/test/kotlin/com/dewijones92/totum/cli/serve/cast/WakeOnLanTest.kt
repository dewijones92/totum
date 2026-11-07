package com.dewijones92.totum.cli.serve.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class WakeOnLanTest {

    private val lg = byteArrayOf(0x20, 0x28, 0xbc.toByte(), 0x6b, 0xf7.toByte(), 0xf8.toByte())

    @Test
    fun `a MAC is read with colons or dashes, in either case`() {
        assertArrayEquals(lg, WakeOnLan.parseMac("20:28:bc:6b:f7:f8"))
        assertArrayEquals(lg, WakeOnLan.parseMac(" 20-28-BC-6B-F7-F8 "))
    }

    @Test
    fun `anything that is not six hex pairs is refused`() {
        listOf(
            "",
            "20:28:bc:6b:f7",
            "20:28:bc:6b:f7:f8:00",
            "zz:28:bc:6b:f7:f8",
            "2028bc6bf7f8",
            "20:28:bc:6b:f7:f"
        ).forEach {
            assertNull("'$it' is not a MAC", WakeOnLan.parseMac(it))
        }
    }

    @Test
    fun `a MAC is shown the same way whatever way it was written`() {
        assertEquals("20:28:bc:6b:f7:f8", WakeOnLan.show(lg))
    }

    @Test
    fun `the magic packet is six 0xFF bytes then the MAC sixteen times`() {
        val packet = WakeOnLan.magicPacket(lg)

        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        repeat(16) { n -> assertArrayEquals(lg, packet.copyOfRange(6 + n * 6, 12 + n * 6)) }
    }

    @Test
    fun `broadcasts are the global one, the host's own subnet, then any configured`() {
        assertEquals(
            listOf("255.255.255.255", "192.168.0.255", "10.0.0.255"),
            WakeOnLan.broadcastsFor("192.168.0.219", listOf("10.0.0.255", "192.168.0.255", " ")),
        )
    }

    @Test
    fun `a host that is not an IPv4 address has only the global broadcast and the configured ones`() {
        assertEquals(listOf("255.255.255.255"), WakeOnLan.broadcastsFor("lg.local", emptyList()))
        assertEquals(listOf("255.255.255.255", "10.1.2.255"), WakeOnLan.broadcastsFor("lg.local", listOf("10.1.2.255")))
    }

    @Test
    fun `the UDP sender delivers the packet bytes to the address and port`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = RECEIVE_MS
            val packet = WakeOnLan.magicPacket(lg)

            UdpWakeSender().send(packet, "127.0.0.1", listener.localPort)

            val got = DatagramPacket(ByteArray(packet.size + 10), packet.size + 10)
            listener.receive(got)
            assertArrayEquals(packet, got.data.copyOf(got.length))
        }
    }

    private companion object {
        const val RECEIVE_MS = 3_000
    }
}
