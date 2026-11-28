package com.dewijones92.totum.diagnostics.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerHoldRecogniserTest {
    private val width = 1080f
    private val zone = CornerZone.topRight(widthPx = width, sizePx = 144f)
    private val recogniser = CornerHoldRecogniser(inZone = zone::contains, holdMs = 1_500, slopPx = 24f)

    private val inCorner = 1_040f to 40f

    private fun pressInCorner(at: Long = 0, pointer: Long = 1) =
        recogniser.down(pointer, inCorner.first, inCorner.second, at)

    @Test fun holdingInTheCornerForTheWholeTimeFires() {
        pressInCorner(at = 0)
        assertEquals(CornerHoldRecogniser.Verdict.Fired, recogniser.tick(1_500))
    }

    @Test fun oneMillisecondShortOfTheTimeDoesNotFire() {
        pressInCorner(at = 100)
        assertTrue(recogniser.tick(1_599) is CornerHoldRecogniser.Verdict.Holding)
    }

    @Test fun aTapNeverFiresAndIsAbandonedAsShort() {
        pressInCorner(at = 0)
        val verdict = recogniser.up(1, 180)
        assertTrue(verdict is CornerHoldRecogniser.Verdict.Abandoned)
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.tick(5_000))
    }

    @Test fun aHoldThatEndsBeforeTheTimeIsNotAReport() {
        pressInCorner(at = 0)
        assertTrue(recogniser.up(1, 1_499) is CornerHoldRecogniser.Verdict.Abandoned)
    }

    @Test fun aReleaseAtTheTimeFiresEvenIfNoTimerRan() {
        pressInCorner(at = 0)
        assertEquals(CornerHoldRecogniser.Verdict.Fired, recogniser.up(1, 1_500))
    }

    @Test fun aPressOutsideTheCornerIsNeverTracked() {
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.down(1, 500f, 40f, 0))
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.tick(2_000))
    }

    @Test fun aPressJustBelowTheCornerIsNeverTracked() {
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.down(1, 1_040f, 200f, 0))
    }

    @Test fun driftingWithinTheSlopKeepsHolding() {
        pressInCorner(at = 0)
        val verdict = recogniser.move(1, inCorner.first - 10f, inCorner.second + 10f, 400)
        assertTrue(verdict is CornerHoldRecogniser.Verdict.Holding)
        assertEquals(CornerHoldRecogniser.Verdict.Fired, recogniser.tick(1_500))
    }

    @Test fun driftingPastTheSlopAbandons() {
        pressInCorner(at = 0)
        val verdict = recogniser.move(1, inCorner.first - 60f, inCorner.second, 400)
        assertTrue(verdict is CornerHoldRecogniser.Verdict.Abandoned)
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.tick(1_500))
    }

    @Test fun aSecondFingerAbandons() {
        pressInCorner(at = 0, pointer = 1)
        assertTrue(recogniser.down(2, 500f, 900f, 300) is CornerHoldRecogniser.Verdict.Abandoned)
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.tick(1_500))
    }

    @Test fun aFingerAlreadyDownElsewhereBlocksTheCorner() {
        recogniser.down(1, 500f, 900f, 0)
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.down(2, 1_040f, 40f, 100))
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.tick(2_000))
    }

    @Test fun itFiresOnceAndThenOwnsThePointerUntilItLifts() {
        pressInCorner(at = 0)
        recogniser.tick(1_500)
        assertTrue(recogniser.owns(1))
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.tick(3_000))
        recogniser.up(1, 3_100)
        assertFalse(recogniser.owns(1))
    }

    @Test fun itCanFireAgainOnTheNextHold() {
        pressInCorner(at = 0)
        assertEquals(CornerHoldRecogniser.Verdict.Fired, recogniser.tick(1_500))
        recogniser.up(1, 1_600)
        pressInCorner(at = 5_000)
        assertEquals(CornerHoldRecogniser.Verdict.Fired, recogniser.tick(6_500))
    }

    @Test fun cancellingAbandonsTheHold() {
        pressInCorner(at = 0)
        assertTrue(recogniser.cancelAll("window lost focus", 300) is CornerHoldRecogniser.Verdict.Abandoned)
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.tick(1_500))
    }

    @Test fun anAbandonedHoldSaysHowLongItWasHeld() {
        pressInCorner(at = 100)
        assertEquals(
            CornerHoldRecogniser.Verdict.Abandoned("released early", heldMs = 700),
            recogniser.up(1, 800),
        )
    }

    @Test fun theTimerIsDueOneHoldAfterThePress() {
        assertEquals(null, recogniser.dueAtMs())
        pressInCorner(at = 250)
        assertEquals(1_750L, recogniser.dueAtMs())
        recogniser.tick(1_750)
        assertEquals(null, recogniser.dueAtMs())
    }

    @Test fun aSecondFingerAfterTheFireChangesNothing() {
        pressInCorner(at = 0, pointer = 1)
        recogniser.tick(1_500)
        assertEquals(CornerHoldRecogniser.Verdict.Idle, recogniser.down(2, 500f, 900f, 1_600))
        assertTrue(recogniser.owns(1))
    }

    @Test fun remainingTimeCountsDownFromThePress() {
        pressInCorner(at = 200)
        assertEquals(1_000L, recogniser.remainingMs(700))
    }

    @Test fun theZoneReachesAStatusBarHeightFurtherDownSoItSitsBelowTheClock() {
        val tall = CornerZone.topRight(widthPx = width, sizePx = 144f, topInsetPx = 72f)
        assertTrue(tall.contains(width - 10f, 210f))
        assertFalse(tall.contains(width - 10f, 217f))
        assertFalse(tall.contains(width - 145f, 100f))
    }

    @Test fun theZoneIsTheTopRightSquareAndNothingElse() {
        assertTrue(zone.contains(width - 1f, 1f))
        assertTrue(zone.contains(width - 143f, 143f))
        assertFalse(zone.contains(width - 145f, 10f))
        assertFalse(zone.contains(width - 10f, 145f))
        assertFalse(zone.contains(10f, 10f))
    }
}
