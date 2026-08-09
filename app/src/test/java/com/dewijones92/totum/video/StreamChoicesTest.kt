package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What you picked by hand carries to the next video; what you have not picked follows the phone.
 *
 * Dewi, 2026-08-09: *"I want everything to be maintained going to the next video in auto play …
 * I don't want that ever to change unless I manually change it"*.
 */
class StreamChoicesTest {

    private val choices = StreamChoices(deviceLanguages = { PHONE })

    // ---- audio language -------------------------------------------------------------------

    @Test
    fun `with nothing chosen, resolving follows the phone`() {
        assertNull(choices.audioLanguage)
        assertEquals(PHONE, choices.preferredAudioLanguages())
    }

    @Test
    fun `a chosen track beats the phone's own language`() {
        choices.chooseAudio("de-DE")

        assertEquals(listOf("de-DE"), choices.preferredAudioLanguages())
    }

    @Test
    fun `the choice holds for every video after it`() {
        choices.chooseAudio("de-DE")

        repeat(VIDEOS) { assertEquals(listOf("de-DE"), choices.preferredAudioLanguages()) }
    }

    @Test
    fun `choosing again replaces it`() {
        choices.chooseAudio("de-DE")
        choices.chooseAudio("en-US")

        assertEquals(listOf("en-US"), choices.preferredAudioLanguages())
    }

    @Test
    fun `a phone with several languages offers all of them, in order`() {
        assertEquals("the phone's second language is a real preference too", PHONE, choices.preferredAudioLanguages())
    }

    // ---- quality --------------------------------------------------------------------------

    @Test
    fun `with nothing chosen, the best the connection allows plays`() {
        assertEquals(1080, choices.qualityFrom(ladder(360, 720, 1080, 2160), cap = 1080)?.height)
    }

    @Test
    fun `a chosen height holds on the next video`() {
        choices.chooseHeight(720)

        assertEquals(720, choices.qualityFrom(ladder(360, 720, 1080, 2160), cap = NO_CAP)?.height)
    }

    @Test
    fun `the nearest height at or below yours is taken when yours is not offered`() {
        // Going UP would hand you more data than you asked for, on a choice you made deliberately.
        choices.chooseHeight(720)

        assertEquals(480, choices.qualityFrom(ladder(240, 480, 1080), cap = NO_CAP)?.height)
    }

    @Test
    fun `a video published only above your height still plays`() {
        // The alternative is a black screen because you once tapped 480p.
        choices.chooseHeight(480)

        assertEquals(1080, choices.qualityFrom(ladder(1080, 2160), cap = NO_CAP)?.height)
    }

    @Test
    fun `the network's cap still wins over what you asked for`() {
        // Data-saver is a limit, not a preference; asking for 2160p on mobile data must not lift it.
        choices.chooseHeight(2160)

        assertEquals(480, choices.qualityFrom(ladder(360, 480, 1080, 2160), cap = 480)?.height)
    }

    @Test
    fun `your height wins when it is BELOW the cap`() {
        choices.chooseHeight(360)

        assertEquals(360, choices.qualityFrom(ladder(360, 720, 1080), cap = 1080)?.height)
    }

    @Test
    fun `a video with no ladder at all chooses nothing rather than guessing`() {
        choices.chooseHeight(720)

        assertNull(choices.qualityFrom(emptyList(), cap = NO_CAP))
    }

    @Test
    fun `a cap below everything offered still yields the smallest allowed set`() {
        // Nothing is allowed, so nothing is chosen and the caller falls back to the default
        // stream — the behaviour that was there before any of this, deliberately unchanged.
        assertNull(choices.qualityFrom(ladder(720, 1080), cap = 240))
    }

    private fun ladder(vararg heights: Int) = heights.sortedDescending().map {
        VideoQuality(
            id = "$it",
            label = "${it}p",
            height = it,
            videoUrl = HttpUrl.of("https://cdn.test/$it"),
            audioUrl = null,
        )
    }

    private companion object {
        val PHONE = listOf("en", "cy")
        const val NO_CAP = Int.MAX_VALUE
        const val VIDEOS = 10
    }
}
