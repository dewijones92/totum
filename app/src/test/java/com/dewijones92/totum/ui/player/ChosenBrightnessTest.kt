package com.dewijones92.totum.ui.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The brightness applies in fullscreen only, and is remembered when you leave.
 *
 * Two separate promises, both broken at some point:
 *
 * - The override must be dropped when fullscreen ends, or the rest of the app inherits it (Dewi,
 *   2026-08-09: *"only applied if the video has been played in full screen"*). Dropping it used to
 *   drop the CHOICE too, so on a queue the brightness reset at every track change — the app
 *   changing a setting nobody touched, which is what Dewi asked to stop on 2026-08-05.
 * - Whichever ORDER Compose disposes and composes the two stages in, the answer must be the same.
 *   That is why this counts owners rather than holding a flag.
 */
class ChosenBrightnessTest {

    @Before
    @After
    fun clean() = ChosenBrightness.forget()

    @Test
    fun `nothing chosen means follow the system`() {
        assertEquals(ChosenBrightness.FOLLOW_SYSTEM, ChosenBrightness.value, 0f)
        assertFalse("with nothing to restore, a video must not override the window", ChosenBrightness.isSet)
    }

    @Test
    fun `a gesture is remembered`() {
        ChosenBrightness.choose(0.3f)

        assertEquals(0.3f, ChosenBrightness.value, 0f)
        assertTrue(ChosenBrightness.isSet)
    }

    /** Full dark is a real choice, not "unset" — the boundary the negative sentinel sits next to. */
    @Test
    fun `zero is a choice, not the absence of one`() {
        ChosenBrightness.choose(0f)

        assertEquals(0f, ChosenBrightness.value, 0f)
        assertTrue("0 must count as set, or full dark would be silently ignored", ChosenBrightness.isSet)
    }

    @Test
    fun `a later gesture replaces the earlier one`() {
        ChosenBrightness.choose(0.3f)
        ChosenBrightness.choose(0.8f)

        assertEquals(0.8f, ChosenBrightness.value, 0f)
    }

    /** A drag past either end is clamped rather than stored as nonsense. */
    @Test
    fun `values outside the range are clamped`() {
        ChosenBrightness.choose(2f)
        assertEquals(1f, ChosenBrightness.value, 0f)

        ChosenBrightness.choose(-5f)
        assertEquals("and never back to the follow-the-system sentinel", 0f, ChosenBrightness.value, 0f)
    }

    // ---- the window is released by the LAST stage, not the first --------------------------------

    /**
     * Dewi, 2026-08-08: *"the brightness … is turned down when I go into full screen video"*.
     *
     * Going fullscreen swaps one subtree for another, so for a moment two stages exist: the incoming
     * one has been composed and the outgoing one has not yet been disposed. Whichever order those
     * two run in, the window must still be showing the chosen brightness at the end — that
     * order-independence IS the fix, because Compose ran them in the order that lost it.
     */
    @Test
    fun `the brightness survives a stage swap, whichever way round it happens`() {
        ChosenBrightness.choose(0.87f)
        ChosenBrightness.fullscreenAppeared()

        // New stage composes before the old one is disposed — the real Compose order, and the bug.
        assertEquals(0.87f, ChosenBrightness.fullscreenAppeared(), 0f)
        assertEquals(
            "the outgoing stage must not take the override with it",
            0.87f,
            ChosenBrightness.fullscreenDisappeared(),
            0f
        )

        // ...and the other way round, which is what a different Compose version might do.
        assertEquals(FOLLOW_SYSTEM_FOR_A_MOMENT, ChosenBrightness.fullscreenDisappeared(), 0f)
        assertEquals(0.87f, ChosenBrightness.fullscreenAppeared(), 0f)
    }

    /** Leaving fullscreen hands the screen back to the phone — the whole point of the release. */
    @Test
    fun `the last fullscreen stage to go releases the window`() {
        ChosenBrightness.choose(0.87f)
        ChosenBrightness.fullscreenAppeared()

        assertEquals(ChosenBrightness.FOLLOW_SYSTEM, ChosenBrightness.fullscreenDisappeared(), 0f)
        assertEquals("but the choice itself is kept for the sitting", 0.87f, ChosenBrightness.value, 0f)
    }

    @Test
    fun `with no choice made a fullscreen stage does not override anything`() {
        assertEquals(ChosenBrightness.FOLLOW_SYSTEM, ChosenBrightness.fullscreenAppeared(), 0f)
    }

    /**
     * An unbalanced release must not strand the count below zero: every later stage would then
     * appear to be "not the first" and the brightness could never be applied again — a silent,
     * permanent failure far worse than one stray release.
     */
    @Test
    fun `an unbalanced release cannot strand the count below zero`() {
        ChosenBrightness.choose(0.87f)
        repeat(3) { ChosenBrightness.fullscreenDisappeared() }

        assertEquals(0, ChosenBrightness.fullscreenStagesOnScreen)
        assertEquals("a stage appearing afterwards must still work", 0.87f, ChosenBrightness.fullscreenAppeared(), 0f)
    }

    /** A brightness chosen while stages are on screen applies to them immediately. */
    @Test
    fun `choosing while a stage is on screen takes effect at once`() {
        ChosenBrightness.fullscreenAppeared()
        assertEquals(ChosenBrightness.FOLLOW_SYSTEM, ChosenBrightness.windowBrightness, 0f)

        ChosenBrightness.choose(0.4f)

        assertEquals(0.4f, ChosenBrightness.windowBrightness, 0f)
    }

    /**
     * A windowed stage never registers at all, so the count stays at zero and the window follows
     * the phone. Stated here as well as in the instrumented test because this is the invariant the
     * whole object rests on: the count means FULLSCREEN stages.
     */
    @Test
    fun `a window with no fullscreen stage follows the system however bright the choice`() {
        ChosenBrightness.choose(1f)

        assertEquals(0, ChosenBrightness.fullscreenStagesOnScreen)
        assertEquals(ChosenBrightness.FOLLOW_SYSTEM, ChosenBrightness.windowBrightness, 0f)
    }

    /** Going back into fullscreen returns to what you were watching at, rather than the system's. */
    @Test
    fun `the choice is still there the next time fullscreen opens`() {
        ChosenBrightness.choose(0.87f)
        ChosenBrightness.fullscreenAppeared()
        ChosenBrightness.fullscreenDisappeared()

        assertEquals(0.87f, ChosenBrightness.fullscreenAppeared(), 0f)
    }

    /** A whole sitting of toggling must not drift the count, or the override sticks or vanishes. */
    @Test
    fun `many fullscreen visits leave the count exactly where it started`() {
        ChosenBrightness.choose(0.5f)
        repeat(TOGGLES) {
            ChosenBrightness.fullscreenAppeared()
            ChosenBrightness.fullscreenDisappeared()
        }

        assertEquals(0, ChosenBrightness.fullscreenStagesOnScreen)
        assertEquals(ChosenBrightness.FOLLOW_SYSTEM, ChosenBrightness.windowBrightness, 0f)
    }

    private companion object {
        /**
         * Between the old stage going and the new one arriving there is genuinely no video on
         * screen, so following the system is the correct answer for that instant. It is never seen:
         * both calls land in the same frame, and the window shows whatever was set last.
         */
        const val FOLLOW_SYSTEM_FOR_A_MOMENT = -1f
        const val TOGGLES = 20
    }
}
