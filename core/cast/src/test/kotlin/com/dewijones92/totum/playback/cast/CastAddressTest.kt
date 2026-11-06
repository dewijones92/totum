package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastAddressTest {

    private val vpn = CastNetwork(wifi = true, ethernet = false, vpn = true, ipv4 = listOf("10.13.13.2"))
    private val wlan = CastNetwork(wifi = true, ethernet = false, vpn = false, ipv4 = listOf("192.168.0.42"))

    @Test
    fun `a VPN over Wi-Fi is passed over for the Wi-Fi itself`() {
        assertEquals("192.168.0.42", CastAddress.choose(listOf(vpn, wlan)))
    }

    @Test
    fun `with only the VPN there is nothing the TV can reach`() {
        assertNull(CastAddress.choose(listOf(vpn)))
    }

    @Test
    fun `wired ethernet will do when there is no Wi-Fi`() {
        val wired = CastNetwork(wifi = false, ethernet = true, vpn = false, ipv4 = listOf("192.168.0.50"))
        val mobile = CastNetwork(wifi = false, ethernet = false, vpn = false, ipv4 = listOf("100.64.1.2"))
        assertEquals("192.168.0.50", CastAddress.choose(listOf(mobile, wired)))
    }
}
