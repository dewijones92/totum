package com.dewijones92.totum.data.torrent

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CinemetaTest {

    private val server = MockWebServer()
    private val asked = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun cinemeta() = Cinemeta(OkHttpClient(), server.url("").toString().trimEnd('/'))

    private fun catalogue(vararg entries: Pair<String, Pair<String, String>>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked += request.target
                val hit = entries.firstOrNull { request.target.contains("search=${it.first}.json") }
                val body = hit?.let { (_, entry) ->
                    """{"metas":[{"imdb_id":"${entry.first}","name":"${entry.second.substringBefore('|')}",""" +
                        """"releaseInfo":"${entry.second.substringAfter('|')}"}]}"""
                } ?: """{"metas":[]}"""
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
    }

    @Test
    fun `a release name is looked up by its title and year and wears the poster for the IMDb id`() = runTest {
        catalogue("Spinal%20Tap%20II%3A%20The%20End%20Continues" to ("tt9999999" to "Spinal Tap II: The End Continues|2025"))

        val poster = cinemeta().posterFor(
            "Spinal Tap II: The End Continues (2025) (1080p BluRay x265 10bit EAC3 Atmos 7.1 r00t) [QxR]",
        )

        assertEquals("https://images.metahub.space/poster/medium/tt9999999/img", poster)
    }

    @Test
    fun `a pack's long title is shortened until the catalogue knows it`() = runTest {
        catalogue("The%20Matrix" to ("tt0133093" to "The Matrix|1999"))

        val poster = cinemeta().posterFor("The Matrix 1-4 Pack 1999-2021 REMASTERED 1080p BluRay")

        assertEquals("https://images.metahub.space/poster/medium/tt0133093/img", poster)
        assertEquals(true, asked.size > 1)
    }

    @Test
    fun `a name the catalogue does not know has no poster`() = runTest {
        catalogue()

        assertNull(cinemeta().posterFor("Totally.Unknown.Thing.2031.1080p"))
    }
}
