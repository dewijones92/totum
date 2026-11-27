package com.dewijones92.totum.ui.report

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.BuildConfig
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.diagnostics.DiagnosticsStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class QuickReportOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as TotumApplication).container
    private var savedBase = ""
    private var savedKey = ""
    private var savedToken = ""
    private var savedGesture = true

    @Before
    fun signInAndTurnTheGestureOn() {
        val now = container.appPreferences.settings.value
        savedBase = now.homeServerBase
        savedKey = now.prowlarrApiKey
        savedToken = now.homeServerToken
        savedGesture = now.reportGesture
        container.appPreferences.setHomeServer(BuildConfig.HOME_SERVER.ifBlank { "example.org" }, now.prowlarrApiKey)
        container.appPreferences.setHomeServerToken("test-token-not-real")
        container.appPreferences.setReportGesture(true)
    }

    @After
    fun restore() {
        container.appPreferences.setHomeServer(savedBase, savedKey)
        container.appPreferences.setHomeServerToken(savedToken)
        container.appPreferences.setReportGesture(savedGesture)
    }

    private fun screen() {
        compose.setContent {
            QuickReportHost(container) {
                Box(Modifier.fillMaxSize().testTag("screen")) { Text("Something Dewi is looking at") }
            }
        }
    }

    private fun holdTheCorner() {
        compose.onNodeWithTag("screen").performTouchInput { down(Offset(width - 20f, 20f)) }
        compose.mainClock.advanceTimeBy(HOLD_PLUS_MS)
        compose.waitUntil(SHEET_MS) {
            compose.onAllNodesWithTagCount(QUICK_REPORT_SHEET_TAG) > 0
        }
        compose.onNodeWithTag("screen").performTouchInput { up() }
    }

    private fun ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun holdingTheCornerTakesAScreenshotAndTheNoteSendsWithIt() {
        val before = DiagnosticsStore.pending(context).toSet()
        screen()

        holdTheCorner()
        compose.onNodeWithTag("$QUICK_REPORT_THUMBNAIL_TAG-0").assertIsDisplayed()
        compose.onNodeWithTag(QUICK_REPORT_NOTE_TAG).performTextInput(NOTE)
        compose.onNodeWithTag(QUICK_REPORT_SEND_TAG).performClick()
        compose.waitUntil(REPORT_MS) { DiagnosticsStore.pending(context).any { it !in before } }

        val report = JSONObject(DiagnosticsStore.pending(context).first { it !in before }.readText())
        assertEquals(NOTE, report.getString("note"))
        val shots = report.getJSONArray("screenshots")
        assertEquals(1, shots.length())
        assertEquals("screenshot-1.jpg", shots.getJSONObject(0).getString("name"))
        assertTrue(shots.getJSONObject(0).getInt("bytes") > 0)
    }

    @Test
    fun aSecondReportCanBeHeldAndSentAfterTheFirst() {
        val before = DiagnosticsStore.pending(context).toSet()
        screen()

        repeat(2) { round ->
            holdTheCorner()
            compose.onNodeWithTag(QUICK_REPORT_NOTE_TAG).performTextInput("quick report ${round + 1}")
            compose.onNodeWithTag(QUICK_REPORT_SEND_TAG).performClick()
            compose.waitUntil(REPORT_MS) { DiagnosticsStore.pending(context).count { it !in before } > round }
            compose.waitUntil(SHEET_MS) { compose.onAllNodesWithTagCount(QUICK_REPORT_SHEET_TAG) == 0 }
        }

        val notes = DiagnosticsStore.pending(context).filter { it !in before }
            .map { JSONObject(it.readText()).getString("note") }
        assertEquals(setOf("quick report 1", "quick report 2"), notes.toSet())
    }

    @Test
    fun withTheGestureOffNothingOpens() {
        container.appPreferences.setReportGesture(false)
        screen()

        compose.onNodeWithTag("screen").performTouchInput { down(Offset(width - 20f, 20f)) }
        compose.mainClock.advanceTimeBy(HOLD_PLUS_MS)
        compose.onNodeWithTag("screen").performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithTagCount(QUICK_REPORT_SHEET_TAG))
    }

    private companion object {
        const val NOTE = "the queue jumped to another video"
        const val HOLD_PLUS_MS = 1_700L
        const val SHEET_MS = 10_000L
        const val REPORT_MS = 10_000L
    }
}
