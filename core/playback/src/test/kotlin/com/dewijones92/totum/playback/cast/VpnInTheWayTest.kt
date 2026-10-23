package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.playback.CastProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnInTheWayTest {

    private val wifi = CastNetwork(wifi = true, ethernet = false, vpn = false, ipv4 = listOf("192.168.0.85"))

    private fun vpn(vararg routes: String) =
        CastNetwork(wifi = false, ethernet = false, vpn = true, ipv4 = listOf("10.13.13.2"), routes = routes.toList())

    @Test
    fun `a VPN that routes the home network or everything is in the way`() {
        assertTrue(CastAddress.vpnCarries("192.168.0.85", listOf(wifi, vpn("10.13.13.0/24", "192.168.0.0/24"))))
        assertTrue(CastAddress.vpnCarries("192.168.0.85", listOf(wifi, vpn("0.0.0.0/0"))))
    }

    @Test
    fun `a VPN that leaves the home network alone is not blamed`() {
        assertFalse(CastAddress.vpnCarries("192.168.0.85", listOf(wifi, vpn("10.13.13.0/24", "192.168.1.0/24"))))
        assertFalse(CastAddress.vpnCarries("192.168.0.85", listOf(wifi)))
    }

    @Test
    fun `the strip names the VPN as soon as the stream is handed over, and clears once the TV fetches`() {
        val said = mutableListOf<CastProblem?>()
        val tvs = object : CastTvPort {
            override fun current(): CastTv? = null

            override fun videos(
                refused: Map<String, String>,
                sentHeights: Map<String, Int>,
                fetchingHeights: Map<String, Int>,
            ) = Unit

            override fun problem(now: CastProblem?) {
                said += now
            }
        }
        val reach = CastReach(tvs, vpnInTheWay = { true }, later = { _, _ -> })

        reach.handedOver("item", "token")
        reach.reached("item", "192.168.0.50")

        assertEquals(listOf(CastProblem.VPN_IN_THE_WAY, null), said)
    }
}
