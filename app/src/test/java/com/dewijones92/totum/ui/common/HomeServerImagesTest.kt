package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HomeServerAuth
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeServerImagesTest {

    private val seen = mutableListOf<Request>()

    private val client = homeServerImageClient().newBuilder()
        .addInterceptor(
            Interceptor { chain ->
                seen += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                    .body("".toResponseBody()).build()
            },
        )
        .build()

    @After
    fun forget() {
        HomeServerAuth.use(host = { null }, token = { "" })
    }

    @Test
    fun `a picture from the home server carries this device's token, a picture from anywhere else does not`() {
        HomeServerAuth.use(host = { "totum.example" }, token = { "device-token" })

        client.newCall(Request.Builder().url("https://totum.example/jellyfin/image/abc").build()).execute().close()
        client.newCall(Request.Builder().url("https://i.ytimg.com/vi/x/hq.jpg").build()).execute().close()

        assertEquals("device-token", seen[0].header(HomeServerAuth.HEADER))
        assertNull(seen[1].header(HomeServerAuth.HEADER))
    }

    @Test
    fun `signed out, nothing is sent even to the home server`() {
        HomeServerAuth.use(host = { "totum.example" }, token = { "" })

        client.newCall(Request.Builder().url("https://totum.example/jellyfin/image/abc").build()).execute().close()

        assertNull(seen.single().header(HomeServerAuth.HEADER))
    }
}
