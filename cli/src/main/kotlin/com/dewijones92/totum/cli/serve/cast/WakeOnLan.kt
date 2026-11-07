package com.dewijones92.totum.cli.serve.cast

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

internal fun interface WakeSender {
    fun send(packet: ByteArray, address: String, port: Int)
}

internal class UdpWakeSender : WakeSender {
    override fun send(packet: ByteArray, address: String, port: Int) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(address), port))
        }
    }
}

internal object WakeOnLan {
    const val GLOBAL_BROADCAST = "255.255.255.255"
    val PORTS = listOf(9, 7)

    private const val MAC_BYTES = 6
    private const val REPEATS = 16
    private const val HEX = 16
    private const val OCTET_MAX = 255
    private val PAIR = Regex("[0-9a-fA-F]{2}")
    private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

    fun parseMac(text: String): ByteArray? {
        val pairs = text.trim().split(':', '-')
        if (pairs.size != MAC_BYTES || pairs.any { !PAIR.matches(it) }) return null
        return ByteArray(MAC_BYTES) { pairs[it].toInt(HEX).toByte() }
    }

    fun show(mac: ByteArray): String = mac.joinToString(":") { it.toUByte().toString(HEX).padStart(2, '0') }

    fun magicPacket(mac: ByteArray): ByteArray =
        ByteArray(MAC_BYTES) { 0xFF.toByte() } + ByteArray(MAC_BYTES * REPEATS) { mac[it % MAC_BYTES] }

    fun broadcastsFor(host: String, configured: List<String>): List<String> {
        val subnet = IPV4.matchEntire(host)?.groupValues?.drop(1)?.map(String::toInt)
            ?.takeIf { octets -> octets.all { it <= OCTET_MAX } }
            ?.let { (a, b, c) -> "$a.$b.$c.$OCTET_MAX" }
        val extras = configured.map(String::trim).filter(String::isNotEmpty)
        return (listOf(GLOBAL_BROADCAST) + listOfNotNull(subnet) + extras).distinct()
    }
}
