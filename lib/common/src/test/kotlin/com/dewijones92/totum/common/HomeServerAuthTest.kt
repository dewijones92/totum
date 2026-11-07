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
