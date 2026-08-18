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
 * When the picture is refused but the sound is not, keep the sound.
 *
 * Measured on 2026-08-18, on NASA's 97-minute "Cosmic Dawn", with every client yt-dlp can reach:
 *
 * ```
 * VIDEO formats with a URL: 19   all ANDROID_VR, durable: 0
 * AUDIO formats with a URL: 77   73 durable (WEB_EMBEDDED_PLAYER)
 * ```
 *
 * **There is no durable video stream to choose.** YouTube serves video only over SABR to the clients
 * that would carry a solved `n`, so watching a long video past its first megabyte cannot be made to
 * work by picking better — the quality ladder has nothing to pick. Seeking an hour in failed roughly
 * seven times in ten while the audio-only URL served the same offset 5 times out of 5.
 *
 * So the app stops failing and starts degrading. Losing the picture on a long video is a poor outcome;
 * silence is a broken app, and this is a listening app — every diagnostics report from Dewi's phone
 * carries `settings.playbackMode = AUDIO`.
 *
 * This is the streaming twin of a rule Dewi already settled for downloads (2026-08-14): an audio-only
 * copy does not stand in while a working stream exists, and once the stream has failed every retry the
 * choice is not "audio or video" but **"audio or nothing"**, where skipping is the worse answer. The
 * order below follows from that: a copy on the disk first (no data, cannot stall), then the sound
 * without the picture, and only then move on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepTheSoundWhenThePictureIsRefusedTest {

    private val failures = MutableSharedFlow<StreamFailure>(extraBufferCapacity = 8)
    private val playedFromDisk = mutableListOf<Long>()
    private val playedWithoutPicture = mutableListOf<Long>()
    private var movedOn = 0

    private var diskHasIt = false
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
        playWithoutThePicture = { at ->
            if (soundAvailable) playedWithoutPicture += at
            soundAvailable
        },
        awaitNetwork = { CompletableDeferred<Unit>().await() },
        scope = backgroundScope,
        backoffMs = 0,
    ).also { it.start() }

    private fun refused(at: Long) =
        StreamFailure(MediaItemId("uSMGENDH_QI"), positionMs = at, reason = StreamFailure.Reason.Rejected)

    /** THE case: no copy on disk, but the sound is fetchable. Keep playing. */
    @Test
    fun `a refused picture falls back to the sound`() = runTest {
        soundAvailable = true
        recovery()
        runCurrent()

        repeat(2) {
            failures.emit(refused(at = 3_600_000))
            runCurrent()
        }

        assertEquals("it should have kept the sound going", listOf(3_600_000L), playedWithoutPicture)
        assertEquals("and not abandoned the item", 0, movedOn)
    }

    /** A copy on the disk still wins: no data, and it cannot stall. */
    @Test
    fun `a copy on disk is preferred over a picture-less stream`() = runTest {
        diskHasIt = true
        soundAvailable = true
        recovery()
        runCurrent()

        repeat(2) {
            failures.emit(refused(at = 3_600_000))
            runCurrent()
        }

        assertEquals(listOf(3_600_000L), playedFromDisk)
        assertEquals("the stream should not have been needed", emptyList<Long>(), playedWithoutPicture)
    }

    /** With neither, moving on is still right — just reached last rather than first. */
    @Test
    fun `with no disk copy and no sound it moves on`() = runTest {
        recovery()
        runCurrent()

        repeat(2) {
            failures.emit(refused(at = 500))
            runCurrent()
        }

        assertEquals(1, movedOn)
        assertEquals(emptyList<Long>(), playedWithoutPicture)
    }

    /** It resumes where the picture died, not from the top of a 97-minute video. */
    @Test
    fun `the sound resumes at the position the picture failed`() = runTest {
        soundAvailable = true
        recovery()
        runCurrent()

        repeat(2) {
            failures.emit(refused(at = 4_321_000))
            runCurrent()
        }

        assertEquals(listOf(4_321_000L), playedWithoutPicture)
    }
}
