package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TheQueueLeavesRoomForItsRowsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun queued(id: Int) = PlayableItem(
        item = MediaItem(
            id = MediaItemId("q$id"),
            sourceId = SourceId("s"),
            title = "Row number $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    private fun rowsInView(): Int {
        val viewport = composeTestRule.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        return composeTestRule.onAllNodesWithText("Row number", substring = true).fetchSemanticsNodes().count {
            it.boundsInRoot.top >= viewport.top && it.boundsInRoot.bottom <= viewport.bottom
        }
    }

    @Test
    fun aSmallScreenWithAFullDockStillShowsRows() {
        val container = FakeAppContainer()
        container.playbackQueue.playAll((1..ROWS).map(::queued))
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(DENSITY, 1f)) {
                TotumTheme {
                    Box(Modifier.testTag(VIEWPORT).requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp)) { QueueScreen(container) }
                }
            }
        }
        composeTestRule.waitForIdle()

        val shown = rowsInView()
        assertTrue("only $shown rows fit above a long-title dock at 1080x1920@480; wanted $MIN_ROWS", shown >= MIN_ROWS)
    }

    private companion object {
        const val VIEWPORT = "queue-viewport"
        const val ROWS = 8
        const val DENSITY = 3f
        const val WIDTH_DP = 360
        const val HEIGHT_DP = 365
        const val MIN_ROWS = 2
    }
}
