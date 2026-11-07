package com.dewijones92.totum.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeServerAuthTest {

    @After fun forget() = HomeServerAuth.use({ null }, { "" })

    @Test
    fun `only the home server is sent the token, and only once there is one`() {
        var token = ""
        HomeServerAuth.use({ "totum.home.test" }, { token })

        assertEquals(emptyMap<String, String>(), HomeServerAuth.headersFor("totum.home.test"))
        token = "device-token"
        assertEquals(mapOf("X-Totum-Token" to "device-token"), HomeServerAuth.headersFor("TOTUM.home.test"))
        assertEquals(emptyMap<String, String>(), HomeServerAuth.headersFor("rr2---sn.googlevideo.com"))
        assertEquals(emptyMap<String, String>(), HomeServerAuth.headersFor(null))
    }

    @Test
    fun `with no home server set up nothing is sent anywhere`() {
        HomeServerAuth.use({ null }, { "device-token" })

        assertEquals(emptyMap<String, String>(), HomeServerAuth.headersFor("totum.home.test"))
    }

    @Test
    fun `reports go to the sink of the configured home server and never to anyone else's`() {
        HomeServerAuth.use({ "totum.stranger.test" }, { "stranger-token" }, { "crashlog.stranger.test" })

        assertEquals("https://crashlog.stranger.test/ingest", HomeServerAuth.reportEndpoint())
        assertEquals(mapOf("X-Totum-Token" to "stranger-token"), HomeServerAuth.headersFor("crashlog.stranger.test"))
        assertEquals(emptyMap<String, String>(), HomeServerAuth.headersFor("crashlog.333133333.xyz"))
        assertEquals(false, HomeServerAuth.reportEndpoint()!!.contains("333133333"))
    }

    @Test
    fun `with no home server there is no report endpoint`() {
        HomeServerAuth.use({ null }, { "device-token" })

        assertEquals(null, HomeServerAuth.reportEndpoint())
        assertEquals(emptyMap<String, String>(), HomeServerAuth.headersFor("crashlog.333133333.xyz"))
    }

    @Test
    fun `signed in needs both a home server and a token`() {
        assertEquals(false, HomeServerAuth.isSignedIn(null, "token"))
        assertEquals(false, HomeServerAuth.isSignedIn("", "token"))
        assertEquals(false, HomeServerAuth.isSignedIn("totum.home.test", ""))
        assertEquals(false, HomeServerAuth.isSignedIn("totum.home.test", "  "))
        assertEquals(true, HomeServerAuth.isSignedIn("totum.home.test", "token"))
    }

    @Test
    fun `the registered sources answer signed in`() {
        HomeServerAuth.use({ "totum.home.test" }, { "token" })
        assertEquals(true, HomeServerAuth.signedIn())
        HomeServerAuth.use({ "totum.home.test" }, { "" })
        assertEquals(false, HomeServerAuth.signedIn())
    }
}
