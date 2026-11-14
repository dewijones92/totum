package com.dewijones92.totum.screens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenAnnouncerStateTest {

    private val minute = 60_000L
    private val page = "http://192.168.0.42:8765/screen#key=k"
    private val posted = mutableListOf<JsonObject>()
    private var accepted = true
    private var mutedBecause: String? = null
    private val pages = MutableStateFlow<String?>(page)
    private val now = MutableStateFlow(PhoneNow.IDLE)

    private fun TestScope.announcer() {
        ScreenAnnouncer(
            backgroundScope,
            post = {
                posted += Json.parseToJsonElement(it).jsonObject
                accepted
            },
            identity = { PhoneIdentity("install-1", "Pixel 7") },
            muted = { mutedBecause },
        ).follow(pages, now)
        runCurrent()
    }

    @Test
    fun `starting to play is told at once, with the title`() = runTest {
        announcer()
        assertEquals(1, posted.size)
        assertFalse(posted.single()["playing"]!!.jsonPrimitive.boolean)
        assertNull(posted.single()["title"])

        now.value = PhoneNow(playing = true, title = "Peep Show")
        runCurrent()

        assertEquals(2, posted.size)
        assertTrue(posted.last()["playing"]!!.jsonPrimitive.boolean)
        assertEquals("Peep Show", posted.last()["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a new title while playing is told, and stopping is told`() = runTest {
        now.value = PhoneNow(true, "One")
        announcer()

        now.value = PhoneNow(true, "Two")
        runCurrent()
        now.value = PhoneNow(false, "Two")
        runCurrent()

        assertEquals(listOf("One", "Two", "Two"), posted.map { it["title"]!!.jsonPrimitive.content })
        assertEquals(listOf(true, true, false), posted.map { it["playing"]!!.jsonPrimitive.boolean })
    }

    @Test
    fun `the same state emitted again is not told again`() = runTest {
        announcer()

        now.value = PhoneNow.IDLE
        pages.value = page
        runCurrent()

        assertEquals(1, posted.size)
    }

    @Test
    fun `a new page address is told`() = runTest {
        announcer()

        pages.value = "http://192.168.0.77:8765/screen#key=k2"
        runCurrent()

        assertEquals(2, posted.size)
        assertEquals("http://192.168.0.77:8765/screen#key=k2", posted.last()["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without a page there is nothing to tell, and the page coming back is told`() = runTest {
        pages.value = null
        announcer()
        assertTrue(posted.isEmpty())

        pages.value = page
        runCurrent()

        assertEquals(1, posted.size)
    }

    @Test
    fun `every announcement carries the install id and model, never anything of the key but the address`() = runTest {
        announcer()

        val sent = posted.single()
        assertEquals("install-1", sent["installId"]!!.jsonPrimitive.content)
        assertEquals("Pixel 7", sent["model"]!!.jsonPrimitive.content)
        assertEquals("screen", sent["kind"]!!.jsonPrimitive.content)
        assertEquals(setOf("kind", "url", "installId", "model", "playing"), sent.keys)
    }

    @Test
    fun `a refused announcement is tried again at the next interval, not in a tight loop`() = runTest {
        accepted = false
        announcer()

        advanceTimeBy(29 * minute)
        assertEquals(1, posted.size)
        advanceTimeBy(2 * minute)

        assertEquals(2, posted.size)
    }

    @Test
    fun `a muted phone tells nobody even when its state changes`() = runTest {
        mutedBecause = "an emulator"
        announcer()

        now.value = PhoneNow(true, "Peep Show")
        runCurrent()

        assertTrue(posted.isEmpty())
    }
}
