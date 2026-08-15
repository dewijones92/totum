package com.dewijones92.totum.diagnostics

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A report has to answer the questions we send it to answer.
 *
 * This exists because 0.1.346 did not. It carried the whole 97-item queue, every setting and a full
 * logcat, and **not one word about what was on the disk** — so "was the item downloaded?", the first
 * question of an offline-playback report, could not be answered from it at all and the diagnosis had
 * to come from reading code instead (2026-08-06).
 *
 * Asserting the KEYS rather than their values, because the values depend on the device and the keys
 * are the contract: whoever removes one has to see this fail and decide deliberately. Dewi's rule —
 * verbose logs and diagnostics are a MUST, and must be readable after the fact.
 */
class DiagnosticsContentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container

    private val queued = PlayableItem(
        MediaItem(
            id = MediaItemId("diagnostics-probe"),
            sourceId = SourceId("test"),
            title = "an item to report on",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.test/probe.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    @Before
    fun queueSomethingToReportOn() = runBlocking(Dispatchers.Main) {
        container.playbackQueue.enqueue(queued)
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.Main) {
        container.playbackQueue.clear()
    }

    @Test
    fun `a diagnostics report says what is on the disk and how the heap looks`() {
        val before = DiagnosticsStore.pending(context).toSet()
        container.sendDiagnostics("written by DiagnosticsContentTest")

        val written = DiagnosticsStore.pending(context).firstOrNull { it !in before }
        assertTrue("no report was written at all", written != null)
        val report = JSONObject(written!!.readText())
        val state = report.optJSONObject("state")
        assertTrue("the report carries no state block", state != null)

        REQUIRED_STATE_KEYS.forEach { key ->
            assertTrue(
                "a report without \"$key\" cannot answer the question it exists for. " +
                    "Present: ${state!!.keys().asSequence().sorted().toList()}",
                state.has(key),
            )
        }
        REQUIRED_TOP_LEVEL.forEach { key ->
            assertTrue("the report is missing \"$key\"", report.has(key))
        }
        // The per-item line specifically, since a count cannot say whether the item that was TAPPED
        // was on the disk — which is exactly what 0.1.346 could not tell us.
        assertTrue(
            "the queue's per-item download states must name the item",
            state!!.getString("downloads.queueStates").contains("an item to report on"),
        )
    }

    /**
     * The note reaches the report, which is the whole point of asking for one.
     *
     * Dewi, 2026-08-15: he wanted a box to type what went wrong into, because a report is four
     * hundred events and the sentence "warfronts video not playing, skipping to another Rest Is
     * Politics video" is what made 0.1.383 diagnosable. A box that types into nothing would be
     * indistinguishable from one that works, right up until the report that needed it.
     */
    @Test
    fun `what the user typed reaches the report`() {
        val typed = "tapped the WarFronts video and it jumped to another one"
        val before = DiagnosticsStore.pending(context).toSet()

        container.sendDiagnostics(diagnosticsNote(typed, "Sent by hand from Settings"))

        val written = DiagnosticsStore.pending(context).firstOrNull { it !in before }
        assertTrue("no report was written at all", written != null)
        assertEquals(typed, JSONObject(written!!.readText()).optString("note"))
    }

    private companion object {
        val REQUIRED_STATE_KEYS = listOf(
            "playing.title",
            "playing.positionMs",
            "queue.size",
            "queue.items",
            "downloads.queueReady",
            "downloads.queueWaiting",
            "downloads.queueUnavailableOffline",
            "downloads.queueNotAutomatic",
            "downloads.onDisk",
            "downloads.queueStates",
            "settings.playbackMode",
            "settings.autoDownloadQueue",
            "network.metered",
        )

        /** The heap as numbers, not prose: "was it near the ceiling?" must be comparable. */
        val REQUIRED_TOP_LEVEL = listOf("heapUsedMb", "heapMaxMb", "nativeHeapMb", "appVersion", "gitCommit")
    }
}
