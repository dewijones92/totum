package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLanProbeTest {

    private var clock = 1_000L
    private val asked = mutableListOf<String>()
    private var answer: String? = CastOutPaths.LAN_CHECK_ANSWER
    private var address: String? = "192.168.0.19:8771"

    private val probe = HomeLanProbe(
        address = { address },
        fetch = { url, _ ->
            asked += url
            answer
        },
        now = { clock },
    )

    @After
    fun forget() = HomeLan.use({ null }, {})

    @Test
    fun `the Pi answering its signature on the home address means the phone is on the home network`() {
        assertEquals(true, probe.onHomeLan())
        assertEquals(listOf("http://192.168.0.19:8771/lan-check"), asked)
    }

    @Test
    fun `no answer, or a stranger's answer, means the phone is not on the home network`() {
        answer = null
        assertEquals(false, probe.onHomeLan())

        clock += HomeLanProbe.KEPT_MS + 1
        answer = "<html>some other device</html>"
        assertEquals(false, probe.onHomeLan())
    }

    @Test
    fun `with no home address known yet nothing is judged and nothing is asked`() {
        address = null

        assertNull(probe.onHomeLan())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a verdict is kept for twenty seconds, then asked again`() {
        probe.onHomeLan()
        clock += HomeLanProbe.KEPT_MS - 1
        probe.onHomeLan()
        assertEquals(1, asked.size)

        clock += 2
        probe.onHomeLan()
        assertEquals(2, asked.size)
    }

    @Test
    fun `a different address is asked at once`() {
        probe.onHomeLan()
        address = "10.0.0.2:8771"

        probe.onHomeLan()

        assertEquals(2, asked.size)
        assertEquals("http://10.0.0.2:8771/lan-check", asked.last())
    }

    @Test
    fun `the remembered address is stored once and read back after a restart`() {
        var stored: String? = null
        var saves = 0
        HomeLan.use(
            { stored },
            { address ->
                stored = address
                saves++
            },
        )

        HomeLan.remember(" 192.168.0.19:8771 ")
        HomeLan.remember("192.168.0.19:8771")
        HomeLan.remember("   ")

        assertEquals("192.168.0.19:8771", HomeLan.address())
        assertEquals(1, saves)
        HomeLan.use({ stored }, { stored = it })
        assertEquals("192.168.0.19:8771", HomeLan.address())
        assertFalse(stored.isNullOrBlank())
    }

    @Test
    fun `a probe that cannot connect says no without throwing`() {
        val unreachable = HomeLanProbe({ "127.0.0.1:1" }, timeoutMs = 300)

        assertEquals(false, unreachable.onHomeLan())
    }
}
