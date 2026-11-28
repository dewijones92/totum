package com.dewijones92.totum.queue

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackProblem
import com.dewijones92.totum.playback.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ADuplicateProblemIsToldOnceTest {

    private var clock = 10_000L
    private val attempts = PlayAttempts(now = { clock })
    private val sound = PlaybackProblem.CannotDecode(
        TrackKind.AUDIO,
        "E-AC-3",
        "ERROR_CODE_DECODING_FAILED",
        formatSupported = true,
        mimeType = "audio/eac3",
    )

    @Test
    fun `the same item failing twice within a second is told once`() = runTest {
        val told = mutableListOf<RefusedTap>()
        attempts.taps.onEach { told += it }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val id = MediaItemId("film")

        attempts.announceProblem("Film", id, sound, listen = null)
        clock += 2
        attempts.announceProblem("Film", id, sound, listen = null)

        assertEquals(1, told.size)
    }

    @Test
    fun `a later failure of the same item, or another item failing, is told again`() = runTest {
        val told = mutableListOf<RefusedTap>()
        attempts.taps.onEach { told += it }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        attempts.announceProblem("Film", MediaItemId("film"), sound, listen = null)
        clock += 5_000
        attempts.announceProblem("Film", MediaItemId("film"), sound, listen = null)
        clock += 2
        attempts.announceProblem("Other", MediaItemId("other"), sound, listen = null)

        assertEquals(3, told.size)
    }
}
