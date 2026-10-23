package com.dewijones92.totum.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenAnnouncerTest {

    @Test
    fun `the Pi is told where the page is when it changes, and again every half hour`() = runTest {
        val posted = mutableListOf<String>()
        val pages = MutableStateFlow<String?>(null)
        ScreenAnnouncer(backgroundScope, post = {
            posted += it
            true
        }).follow(pages)
        runCurrent()

        pages.value = "http://192.168.0.42:8765/screen#key=k"
        runCurrent()
        advanceTimeBy(30 * 60_000L + 1)

        assertEquals(2, posted.size)
        assertEquals("""{"kind":"screen","url":"http://192.168.0.42:8765/screen#key=k"}""", posted.first())
    }

    @Test
    fun `an emulator never tells the Pi, so it cannot take over the real phone's bookmark`() = runTest {
        val posted = mutableListOf<String>()
        ScreenAnnouncer(backgroundScope, post = {
            posted += it
            true
        }, muted = { "an emulator" })
            .follow(MutableStateFlow("http://10.0.2.15:8765/screen#key=k"))
        runCurrent()

        assertEquals(emptyList<String>(), posted)
    }
}
