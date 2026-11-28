package com.dewijones92.totum.diagnostics.quick

import com.dewijones92.totum.diagnostics.EncodedScreenshot
import com.dewijones92.totum.diagnostics.MAX_SCREENSHOTS
import com.dewijones92.totum.diagnostics.ReportFate
import com.dewijones92.totum.diagnostics.ReportSubmitter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickReportTest {
    private class Uploaded(val note: String, val shots: List<EncodedScreenshot>)

    private val uploaded = mutableListOf<Uploaded>()
    private var captures = 0
    private var captureResult: () -> EncodedScreenshot? = { shot("screenshot-1.jpg") }

    private fun shot(name: String) = EncodedScreenshot(name, 1080, 2400, 100, "AAAA")

    private fun TestScope.quick() = QuickReport(
        capture = {
            captures++
            captureResult()
        },
        submitter = ReportSubmitter(
            scope = this,
            io = StandardTestDispatcher(testScheduler),
            send = { note, shots, onDone ->
                uploaded += Uploaded(note, shots)
                onDone(ReportFate.Sent)
            },
        ),
        fallbackNote = FALLBACK,
    )

    @Test fun aHoldThenANoteReachesTheUploaderWithTheScreenshotAndTheNote() = runTest {
        val quick = quick()

        quick.trigger()
        assertEquals(QuickReportState.Open(shot("screenshot-1.jpg")), quick.state.value)
        quick.send("the queue jumped to another video", emptyList())
        advanceUntilIdle()

        assertEquals(QuickReportState.Closed, quick.state.value)
        assertEquals(1, uploaded.size)
        assertEquals("the queue jumped to another video", uploaded[0].note)
        assertEquals(listOf("screenshot-1.jpg"), uploaded[0].shots.map { it.name })
    }

    @Test fun aSecondReportCanBeSentRightAfterTheFirst() = runTest {
        val quick = quick()

        quick.trigger()
        quick.send("first", emptyList())
        advanceUntilIdle()
        quick.trigger()
        quick.send("second", emptyList())
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), uploaded.map { it.note })
        assertEquals(2, captures)
    }

    @Test fun aHoldWhileTheSheetIsOpenTakesNoSecondScreenshot() = runTest {
        val quick = quick()

        quick.trigger()
        quick.trigger()

        assertEquals(1, captures)
        assertTrue(quick.state.value is QuickReportState.Open)
    }

    @Test fun aScreenshotThatCannotBeTakenStillOpensTheSheetAndSendsWithoutOne() = runTest {
        captureResult = { null }
        val quick = quick()

        quick.trigger()
        assertEquals(QuickReportState.Open(null), quick.state.value)
        quick.send("no picture", emptyList())
        advanceUntilIdle()

        assertEquals(0, uploaded[0].shots.size)
    }

    @Test fun aCaptureThatThrowsStillOpensTheSheet() = runTest {
        captureResult = { error("window gone") }
        val quick = quick()

        quick.trigger()

        assertEquals(QuickReportState.Open(null), quick.state.value)
    }

    @Test fun dismissingSendsNothingAndAllowsTheNextHold() = runTest {
        val quick = quick()

        quick.trigger()
        quick.dismiss()
        advanceUntilIdle()
        assertTrue(uploaded.isEmpty())
        quick.trigger()

        assertEquals(2, captures)
        assertTrue(quick.state.value is QuickReportState.Open)
    }

    @Test fun screenshotsPickedLaterFollowTheCapturedOneAndAreNumberedInOrder() = runTest {
        val quick = quick()

        quick.trigger()
        quick.send("with extras", listOf(shot("screenshot-1.jpg"), shot("screenshot-2.jpg")))
        advanceUntilIdle()

        assertEquals(
            listOf("screenshot-1.jpg", "screenshot-2.jpg", "screenshot-3.jpg"),
            uploaded[0].shots.map { it.name },
        )
    }

    @Test fun nothingBeyondTheScreenshotLimitIsSent() = runTest {
        val quick = quick()

        quick.trigger()
        quick.send("lots", List(MAX_SCREENSHOTS + 3) { shot("x-$it.jpg") })
        advanceUntilIdle()

        assertEquals(MAX_SCREENSHOTS, uploaded[0].shots.size)
    }

    @Test fun anEmptyNoteSendsTheFallbackSoTheReportIsNeverBlockedOnTyping() = runTest {
        val quick = quick()

        quick.trigger()
        quick.send("   ", emptyList())
        advanceUntilIdle()

        assertEquals(FALLBACK, uploaded[0].note)
    }

    @Test fun sendingWithNoSheetOpenDoesNothing() = runTest {
        val quick = quick()

        quick.send("stray", emptyList())
        advanceUntilIdle()

        assertTrue(uploaded.isEmpty())
    }

    private companion object {
        const val FALLBACK = "Sent from the top-right corner hold"
    }
}
