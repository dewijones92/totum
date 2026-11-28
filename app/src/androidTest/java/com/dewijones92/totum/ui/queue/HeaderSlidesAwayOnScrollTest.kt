package com.dewijones92.totum.ui.queue

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.AppShell
import com.dewijones92.totum.ui.TestShots
import com.dewijones92.totum.ui.common.HeaderScrollHost
import com.dewijones92.totum.ui.common.LocalHeadersFixed
import com.dewijones92.totum.ui.common.scrollsAway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HeaderSlidesAwayOnScrollTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Pi(val targets: List<TargetState>) : TargetsApi {
        override suspend fun targets() = HomeAnswer.Ok(TargetsAnswer(targets))
        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
    }

    private val onTheirQueue =
        listOf(TargetEntry(BackupItem("a", "feed", "Bluey", contentKind = "STANDARD", playbackType = "AUDIO")))
    private val lg = TargetState(Target("lg", "LG S65Q(A4)", TargetKind.SPEAKER), entries = onTheirQueue)

    private fun queued(n: Int) = PlayableItem(
        item = MediaItem(
            id = MediaItemId("row$n"),
            sourceId = SourceId("s"),
            title = "Row $n – Global Institutions Reeling After Microsoft Outage | Novara Live",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$n.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    private fun listTopPx() = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()
        .maxBy { it.boundsInRoot.height }.boundsInRoot.top

    private fun swipeTheList(up: Boolean) {
        compose.onRoot().performTouchInput {
            if (up) {
                swipeUp(startY = height * SWIPE_FROM, endY = height * SWIPE_TO)
            } else {
                swipeDown(startY = height * SWIPE_TO, endY = height * SWIPE_FROM)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aHeaderBlockSlidesAwayOnScrollingDownAndComesBackOnScrollingUp() {
        compose.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalHeadersFixed provides false) {
                    HeaderScrollHost("test") {
                        Column {
                            Box(Modifier.scrollsAway().fillMaxWidth().height(HEADER_DP.dp).testTag("header")) {
                                Text("The header")
                            }
                            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("list")) {
                                items((1..ROWS).toList()) { Text("Row $it", Modifier.height(ROW_DP.dp)) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val density = compose.density.density
        val top = listTopPx()
        compose.onNodeWithTag("header").assertIsDisplayed()

        swipeTheList(up = true)
        val hidden = listTopPx()
        Log.i(
            "dewidebug",
            "header-slide plain: list top ${top / density}dp -> ${hidden / density}dp after scrolling down"
        )
        assertEquals("the list gives the header's ${HEADER_DP}dp to the rows", HEADER_DP * density, top - hidden, 2f)
        compose.onNodeWithTag("header").assertIsNotDisplayed()

        swipeTheList(up = false)
        assertEquals("any scroll up brings the whole header back", top, listTopPx(), 2f)
        compose.onNodeWithTag("header").assertIsDisplayed()
    }

    @Test
    fun theQueueTabHidesItsChipsTitleAndActionsOnScrollingDownAndShowsThemOnScrollingUp() {
        val container = FakeAppContainer(targetsApi = Pi(listOf(lg)))
        container.playbackQueue.playAll((1..QUEUE_ROWS).map(::queued))
        compose.setContent {
            TotumTheme { CompositionLocalProvider(LocalHeadersFixed provides false) { AppShell(container) } }
        }
        compose.waitUntil(WAIT_MS) { container.targets.options.value.size > 1 }
        compose.onNodeWithText(context.getString(R.string.destination_queue)).performClick()
        compose.waitForIdle()
        val phoneChip = compose.onNodeWithText(context.getString(R.string.target_chip_phone))
        phoneChip.assertIsDisplayed()
        val density = compose.density.density
        val top = listTopPx()
        TestShots.take("header-queue-shown")

        swipeTheList(up = true)
        val hidden = listTopPx()
        Log.i(
            "dewidebug",
            "header-slide queue: list top ${top / density}dp -> ${hidden / density}dp after scrolling down"
        )
        assertTrue(
            "the queue's list moved up by the whole header: ${(top - hidden) / density}dp",
            top - hidden > MIN_GAIN_DP * density
        )
        phoneChip.assertIsNotDisplayed()
        TestShots.take("header-queue-hidden")

        swipeTheList(up = false)
        assertEquals("the header is back in full", top, listTopPx(), 2f)
        phoneChip.assertIsDisplayed()
        TestShots.take("header-queue-back")
    }

    private companion object {
        const val HEADER_DP = 120
        const val ROW_DP = 48
        const val ROWS = 80
        const val QUEUE_ROWS = 40
        const val WAIT_MS = 10_000L
        const val MIN_GAIN_DP = 100
        const val SWIPE_FROM = 0.7f
        const val SWIPE_TO = 0.3f
    }
}
