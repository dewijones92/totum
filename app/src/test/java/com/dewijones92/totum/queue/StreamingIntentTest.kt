package com.dewijones92.totum.queue

import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingIntentTest {

    private val dispatcher = StandardTestDispatcher()
    private val x = MediaItemId("x")
    private val y = MediaItemId("y")

    private val wanted = MutableStateFlow<MediaItemId?>(null)
    private val intent = StreamingIntent(wanted, graceMs = 1_000L)
    private val seen = mutableListOf<MediaItemId?>()

    private fun kotlinx.coroutines.test.TestScope.watch() {
        backgroundScope.launch(dispatcher) { intent.now.collect { seen += it } }
        runCurrent()
    }

    @Test
    fun `an item whose route has been decided to stream counts as streaming while the player is still resolving it`() =
        runTest(dispatcher) {
            watch()

            intent.decided(x)
            runCurrent()

            assertEquals(
                "the downloader must be told before the player has the item, or it fetches the stream's own audio",
                listOf(null, x),
                seen,
            )
        }

    @Test
    fun `once the player has the item, whether it wants to play decides, and a pause lifts it after the grace`() =
        runTest(dispatcher) {
            watch()
            intent.decided(x)
            intent.handedOver(x)
            wanted.value = x
            runCurrent()
            assertEquals(x, seen.last())

            wanted.value = null
            runCurrent()
            assertEquals("not at once: a pause is told after the grace", x, seen.last())

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(null, seen.last())
        }

    @Test
    fun `the moment between the call returning and the player saying it plays does not flicker`() =
        runTest(dispatcher) {
            watch()
            intent.decided(x)
            runCurrent()

            intent.handedOver(x)
            runCurrent()
            advanceTimeBy(100)
            wanted.value = x
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(listOf(null, x), seen)
        }

    @Test
    fun `a route that never reached the player stops counting as streaming once its call has returned`() =
        runTest(dispatcher) {
            watch()
            intent.decided(x)
            runCurrent()

            intent.handedOver(x)
            advanceTimeBy(1_001)
            runCurrent()

            assertEquals(listOf(null, x, null), seen)
        }

    @Test
    fun `a newer decision replaces the older, and the older call returning late does not end the newer`() =
        runTest(dispatcher) {
            watch()
            intent.decided(x)
            intent.decided(y)
            runCurrent()

            intent.handedOver(x)
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(y, seen.last())
        }

    @Test
    fun `a route that plays a file, or none, is not streaming even if the player wants that item`() =
        runTest(dispatcher) {
            watch()
            intent.decided(null)
            wanted.value = x
            runCurrent()
            advanceTimeBy(5_000)

            assertEquals(listOf<MediaItemId?>(null), seen)
        }
}
