package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Before giving up the picture, try the protocol YouTube is actually offering.
 *
 * The app already contains a complete, tested SABR client — `:lib:sabr`, UMP framing, protobuf,
 * buffered ranges, run attribution — and on 2026-08-18 it was **switched off**, behind an
 * experimental setting that defaults to false. Meanwhile the failure it exists to solve was
 * happening on the emulator, in full:
 *
 * ```
 * SABR-only streaming experiment ... formats missing a URL
 * HTTP 403 from client ANDROID_VR on a stream with 21577s of its lease left -> Rejected
 * stream still failing after 1 recoveries; giving up on the stream
 * route -> refused ... keeping the sound without the picture from 0ms
 * ```
 *
 * A working route, behind a gate the real failure never opens. That is the fourth instance of this
 * exact shape in one day (see `gated-fallbacks-never-fire`), and the most expensive, because the
 * gate here was not a subtle condition — it was an off switch.
 *
 * The setting stays as it is: SABR as the PRIMARY route would cap every video at 1080p30 and cannot
 * seek, which is a bad default. As a **rescue** it costs nothing when the ordinary route works and
 * saves the picture exactly when the ordinary route is dead. So it goes into the ladder between the
 * two rungs it beats and loses to:
 *
 * | Rung | Why it sits there |
 * |---|---|
 * | a copy on the disk | no data, cannot stall, full quality |
 * | **SABR** | **a picture, at up to 1080p30, over the protocol YouTube is serving** |
 * | the sound without the picture | keeps the item playing when even SABR will not |
 * | move on | nothing left to try |
 *
 * The trade is stated plainly because it is real: SABR's picture is capped at 1080p30 (measured, and
 * re-checked live by `SabrServesWhatWeChooseTest`). A capped picture beats no picture, which is the
 * only comparison that matters at this point in the ladder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TheSabrRungKeepsThePictureTest {

    private val failures = MutableSharedFlow<StreamFailure>(extraBufferCapacity = 8)
    private val playedFromDisk = mutableListOf<Long>()
    private val playedOverSabr = mutableListOf<Long>()
    private val playedWithoutPicture = mutableListOf<Long>()
    private var movedOn = 0

    private var diskHasIt = false
    private var sabrWorks = false
    private var soundAvailable = false

    private fun TestScope.recovery(): StreamRecovery = StreamRecovery(
        failures = failures,
        replay = { true },
        moveOn = {
            movedOn++
            true
        },
        playWithoutTheStream = { at ->
            if (diskHasIt) playedFromDisk += at
            diskHasIt
        },
        playOverSabr = { at ->
            if (sabrWorks) playedOverSabr += at
            sabrWorks
        },
        playWithoutThePicture = { at ->
            if (soundAvailable) playedWithoutPicture += at
            soundAvailable
        },
        awaitNetwork = { CompletableDeferred<Unit>().await() },
        scope = backgroundScope,
        backoffMs = 0,
    ).also { it.start() }

    private fun refused(at: Long) =
        StreamFailure(MediaItemId("jNQXAC9IVRw"), positionMs = at, reason = StreamFailure.Reason.Rejected)

    /** THE case, and the one the emulator was living on 2026-08-18: the picture is saved, not dropped. */
    @Test
    fun `a refused stream tries SABR before giving up the picture`() = runTest {
        sabrWorks = true
        soundAvailable = true
        recovery()
        runCurrent()

        repeat(2) {
            failures.emit(refused(at = 0))
            runCurrent()
        }

        assertEquals("SABR should have been tried", listOf(0L), playedOverSabr)
        assertEquals(
            "and the picture kept, so the sound-only rung is not needed",
            emptyList<Long>(),
            playedWithoutPicture
        )
        assertEquals(0, movedOn)
    }

    /** A copy on the disk still wins: full quality, no data, cannot stall. */
    @Test
    fun `a copy on disk still beats SABR`() = runTest {
        diskHasIt = true
        sabrWorks = true
        recovery()
        runCurrent()

        repeat(2) {
            failures.emit(refused(at = 0))
            runCurrent()
        }

        assertEquals(listOf(0L), playedFromDisk)
        assertEquals("SABR should not have been needed", emptyList<Long>(), playedOverSabr)
    }

    /**
     * When SABR will not serve it either, the sound is still better than skipping.
     *
     * The rung must not swallow the one below it. SABR refuses whole classes of format — every VP9,
     * every 60fps — so "SABR was tried" and "SABR worked" are different facts and the ladder has to
     * keep walking on the difference.
     */
    @Test
    fun `when SABR refuses it too the sound still saves the item`() = runTest {
        sabrWorks = false
        soundAvailable = true
        recovery()
        runCurrent()

        repeat(2) {
            failures.emit(refused(at = 0))
            runCurrent()
        }

        assertEquals(emptyList<Long>(), playedOverSabr)
        assertEquals("the sound rung must still run", listOf(0L), playedWithoutPicture)
        assertEquals(0, movedOn)
    }

    /**
     * Hour-deep, SABR is not offered at all — it cannot start at an arbitrary position yet.
     *
     * Trying and failing would be merely slow; the reason this is asserted is that a SABR rung which
     * "succeeded" from the top of a 97-minute video would throw away an hour of someone's place,
     * which is the exact harm `listen()` caused before it was given the position (2026-08-18).
     */
    @Test
    fun `an hour deep it does not try SABR at all`() = runTest {
        sabrWorks = true
        soundAvailable = true
        recovery()
        runCurrent()

        repeat(2) {
            failures.emit(refused(at = 3_600_000))
            runCurrent()
        }

        assertEquals("SABR cannot seek, so it must not be offered here", emptyList<Long>(), playedOverSabr)
        assertEquals(listOf(3_600_000L), playedWithoutPicture)
    }
}
