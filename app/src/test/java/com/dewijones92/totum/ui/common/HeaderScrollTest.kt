package com.dewijones92.totum.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderScrollTest {

    private val chips = Any()
    private val title = Any()

    private fun scroll(vararg pieces: Pair<Any, Int>) = HeaderScroll("test").also { s ->
        pieces.forEach { (owner, px) -> s.register(owner, px) }
    }

    private fun HeaderScroll.drag(dy: Float): Float =
        connection.onPreScroll(Offset(0f, dy), NestedScrollSource.UserInput).y

    @Test
    fun scrollingDownConsumesTheHeaderHeightBeforeTheListMoves() {
        val s = scroll(chips to 100, title to 140)
        assertEquals(-100f, s.drag(-100f), 0.01f)
        assertEquals(100f / 240f, s.hidden, 0.001f)
        assertEquals(-140f, s.drag(-300f), 0.01f)
        assertEquals(1f, s.hidden, 0.0001f)
        assertEquals("a fully hidden header leaves the rest to the list", 0f, s.drag(-80f), 0f)
    }

    @Test
    fun anyScrollUpBringsTheHeaderBackAndOnlyThenLetsTheListMove() {
        val s = scroll(chips to 100, title to 140)
        s.drag(-500f)
        assertEquals(30f, s.drag(30f), 0.01f)
        assertTrue("a little scroll up already shows some of it", s.hidden < 1f)
        assertEquals(210f, s.drag(400f), 0.01f)
        assertEquals(0f, s.hidden, 0f)
        assertEquals(0f, s.drag(50f), 0f)
    }

    @Test
    fun theHeaderHeightIsConsumedAfterADownwardScroll() {
        val s = scroll(title to 150)
        val shownBefore = Math.round((1f - s.hidden) * 150)
        s.drag(-90f)
        val shownAfter = Math.round((1f - s.hidden) * 150)
        assertEquals(150, shownBefore)
        assertEquals(60, shownAfter)
    }

    @Test
    fun revealPutsEverythingBackAtOnce() {
        val s = scroll(title to 150)
        s.drag(-150f)
        s.reveal("test")
        assertEquals(0f, s.hidden, 0f)
    }

    @Test
    fun fixedHeadersNeverMoveAndAreRevealedWhenFixingStarts() {
        val s = scroll(title to 150)
        s.drag(-100f)
        s.fixed = true
        assertEquals(0f, s.hidden, 0f)
        assertEquals(0f, s.drag(-100f), 0f)
        assertEquals(0f, s.hidden, 0f)
    }

    @Test
    fun withNoHeaderRegisteredNothingIsConsumed() {
        val s = scroll()
        assertEquals(0f, s.drag(-100f), 0f)
        assertEquals(0f, s.hidden, 0f)
    }

    @Test
    fun aPieceThatLeavesStopsCountingTowardTheTravel() {
        val s = scroll(chips to 100, title to 140)
        s.unregister(chips)
        assertEquals(140, s.travelPx)
        assertEquals(-140f, s.drag(-500f), 0.01f)
    }

    @Test
    fun unregisteringTheLastPieceRevealsIt() {
        val s = scroll(title to 140)
        s.drag(-70f)
        s.unregister(title)
        assertEquals(0f, s.hidden, 0f)
    }

    @Test
    fun hiddenAfterIsClampedBetweenShownAndGone() {
        assertEquals(1f, hiddenAfter(0.9f, -100f, 100f), 0f)
        assertEquals(0f, hiddenAfter(0.1f, 100f, 100f), 0f)
        assertEquals(0.5f, hiddenAfter(0f, -50f, 100f), 0.0001f)
        assertEquals(0.3f, hiddenAfter(0.3f, -10f, 0f), 0f)
    }

    @Test
    fun headersStayFixedWithTalkBackOrAnimationsOff() {
        assertTrue(headersFixed(touchExploration = true, animatorScale = 1f))
        assertTrue(headersFixed(touchExploration = false, animatorScale = 0f))
        assertFalse(headersFixed(touchExploration = false, animatorScale = 1f))
        assertFalse(headersFixed(touchExploration = false, animatorScale = 0.5f))
    }
}
