package com.dewijones92.totum.queue

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.StreamHold
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamHoldsTest {

    private val dispatcher = StandardTestDispatcher()
    private val streaming = MutableStateFlow<MediaItemId?>(null)
    private val teeable = MutableStateFlow<Set<String>>(emptySet())
    private val recorded = MutableStateFlow(0)
    private val routes = mutableMapOf<String, String>()
    private val seen = mutableListOf<StreamHold?>()

    private fun kotlinx.coroutines.test.TestScope.watch() {
        backgroundScope.launch(dispatcher) {
            streamHolds(streaming, teeable, recorded) { routes[it] }.collect { seen += it }
        }
        runCurrent()
    }

    @Test
    fun `a playing item is held until its route lands as HLS, then fetched beside the stream`() =
        runTest(dispatcher) {
            watch()

            streaming.value = MediaItemId("v")
            runCurrent()
            assertEquals(StreamHold.Hold("v"), seen.last())

            routes["v"] = "hls"
            recorded.value += 1
            runCurrent()

            val last = seen.last()
            assertTrue(last.toString(), last is StreamHold.Beside)
            assertEquals("v", last!!.id)
        }

    @Test
    fun `an item that is teeable is neither held nor fetched beside`() = runTest(dispatcher) {
        watch()
        routes["a"] = "a direct url"

        streaming.value = MediaItemId("a")
        teeable.value = setOf("a")
        runCurrent()

        assertEquals(null, seen.last())
    }

    @Test
    fun `SABR, a podcast and a live stream stay held after their route is known`() = runTest(dispatcher) {
        watch()
        listOf("sabr", "a direct url", "a live manifest Totum built").forEach { route ->
            routes["p"] = route
            streaming.value = MediaItemId("p")
            recorded.value += 1
            runCurrent()
            assertEquals(route, StreamHold.Hold("p"), seen.last())
        }
    }
}
