package com.dewijones92.totum.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportSubmitterTest {
    private class Sent(val note: String, val shots: List<EncodedScreenshot>)

    private val sent = mutableListOf<Sent>()
    private val announced = mutableListOf<ReportFate>()
    private var fateToGive: ReportFate = ReportFate.Sent
    private var failWith: Throwable? = null

    private fun TestScope.submitter() = ReportSubmitter(
        scope = this,
        io = StandardTestDispatcher(testScheduler),
        send = { note, shots, onDone ->
            failWith?.let { throw it }
            sent += Sent(note, shots)
            onDone(fateToGive)
        },
        announce = { announced += it },
    )

    private fun shot(name: String) = EncodedScreenshot(name, 10, 20, 3, "AAAA")

    @Test fun aSecondReportGoesOutAfterTheFirst() = runTest {
        val submitter = submitter()
        submitter.submit("first", emptyList(), "test")
        advanceUntilIdle()
        submitter.submit("second", listOf(shot("screenshot-1.jpg")), "test")
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), sent.map { it.note })
        assertEquals(2, submitter.completed.value)
        assertEquals(listOf("screenshot-1.jpg"), sent[1].shots.map { it.name })
        assertEquals(listOf<ReportFate>(ReportFate.Sent, ReportFate.Sent), announced)
    }

    @Test fun twoReportsStartedTogetherBothGoOut() = runTest {
        val submitter = submitter()
        submitter.submit("one", emptyList(), "test")
        submitter.submit("two", emptyList(), "test")
        advanceUntilIdle()

        assertEquals(listOf("one", "two"), sent.map { it.note })
    }

    @Test fun nothingIsInFlightOnceTheReportHasBeenHandled() = runTest {
        val submitter = submitter()
        assertEquals(0, submitter.inFlight.value)
        submitter.submit("one", emptyList(), "test")
        advanceUntilIdle()
        assertEquals(0, submitter.inFlight.value)
    }

    @Test fun aFailureToSendDoesNotWedgeTheNextReport() = runTest {
        val submitter = submitter()
        failWith = IllegalStateException("disk full")
        submitter.submit("breaks", emptyList(), "test")
        advanceUntilIdle()
        assertEquals(0, submitter.inFlight.value)
        assertEquals(listOf<ReportFate>(ReportFate.NotWritten), announced)

        failWith = null
        submitter.submit("works", emptyList(), "test")
        advanceUntilIdle()
        assertEquals(listOf("works"), sent.map { it.note })
        assertEquals(ReportFate.Sent, submitter.last.value)
    }

    @Test fun theFateOfTheLastReportIsKept() = runTest {
        val submitter = submitter()
        assertNull(submitter.last.value)
        fateToGive = ReportFate.WillRetry(waiting = 2)
        submitter.submit("offline", emptyList(), "test")
        advanceUntilIdle()
        assertEquals(ReportFate.WillRetry(2), submitter.last.value)
        assertEquals(listOf<ReportFate>(ReportFate.WillRetry(2)), announced)
    }
}
