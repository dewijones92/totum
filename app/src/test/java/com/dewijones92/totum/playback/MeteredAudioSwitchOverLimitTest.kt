package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeteredAudioSwitchOverLimitTest {

    private val item = MediaItemId("a-film")
    private var metered = true
    private var playing: MediaItemId? = item
    private var overLimit = true
    private var switchSucceeds = true
    private var switches = 0
    private val announced = mutableListOf<MediaItemId>()
    private val announcedOverLimit = mutableListOf<MediaItemId>()

    private fun TestScope.switcher() = MeteredAudioSwitch(
        metered = { metered },
        playingVideoId = { playing },
        switchToAudio = {
            switches++
            if (switchSucceeds) playing = null
            switchSucceeds
        },
        announce = { announced += it },
        scope = backgroundScope,
        overLimit = { overLimit },
        announceOverLimit = { announcedOverLimit += it },
    ).also { it.start() }

    @Test
    fun `over the daily limit the switch says the limit was reached, not the ordinary words`() = runTest {
        switcher()

        advanceTimeBy(20_000)

        assertEquals(1, switches)
        assertEquals(listOf(item), announcedOverLimit)
        assertEquals(emptyList<MediaItemId>(), announced)
    }

    @Test
    fun `under the limit the ordinary announcement is used and the limit's is not`() = runTest {
        overLimit = false
        switcher()

        advanceTimeBy(20_000)

        assertEquals(listOf(item), announced)
        assertEquals(emptyList<MediaItemId>(), announcedOverLimit)
    }

    @Test
    fun `over the limit a picture put back by hand is switched off again`() = runTest {
        switcher()
        advanceTimeBy(20_000)
        assertEquals(1, switches)

        advanceTimeBy(10_000)
        playing = item
        advanceTimeBy(5_000)

        assertEquals(2, switches)
        assertEquals(listOf(item, item), announcedOverLimit)
    }

    @Test
    fun `under the limit the same hand-restored picture is respected for the rest of the stretch`() = runTest {
        overLimit = false
        switcher()
        advanceTimeBy(20_000)
        advanceTimeBy(10_000)
        playing = item

        advanceTimeBy(60_000)

        assertEquals(1, switches)
    }

    @Test
    fun `the limit being raised mid-stretch stops the switching`() = runTest {
        switcher()
        advanceTimeBy(20_000)
        advanceTimeBy(10_000)
        overLimit = false
        playing = item

        advanceTimeBy(60_000)

        assertEquals(1, switches)
    }

    @Test
    fun `wifi ends it, even over the limit`() = runTest {
        metered = false
        switcher()

        advanceTimeBy(120_000)

        assertEquals(0, switches)
        assertEquals(emptyList<MediaItemId>(), announcedOverLimit)
    }

    @Test
    fun `a switch that fails over the limit is not announced as done`() = runTest {
        switchSucceeds = false
        switcher()

        advanceTimeBy(20_000)

        assertEquals(emptyList<MediaItemId>(), announcedOverLimit)
        assertEquals(emptyList<MediaItemId>(), announced)
    }

    @Test
    fun `nothing playing with a picture is nothing to switch, over the limit or not`() = runTest {
        playing = null
        switcher()

        advanceTimeBy(60_000)

        assertEquals(0, switches)
    }
}
