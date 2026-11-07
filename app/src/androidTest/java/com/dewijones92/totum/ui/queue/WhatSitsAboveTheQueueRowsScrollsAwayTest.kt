package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
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
import org.junit.Rule
import org.junit.Test

class WhatSitsAboveTheQueueRowsScrollsAwayTest {

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

    private fun show(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(DENSITY, 1f)) {
                TotumTheme { Box(Modifier.testTag(VIEWPORT).requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp)) { content() } }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun thePinnedHeaderIsAllThatStaysWhenTheRowsScroll() {
        val container = FakeAppContainer()
        container.playbackQueue.playAll((1..ROWS).map(::queued))
        show { PhoneQueue(container, above = { Text(ABOVE) }) }
        composeTestRule.onNodeWithText(ABOVE).assertIsDisplayed()

        composeTestRule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(ROWS)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(ABOVE).assertIsNotDisplayed()
    }

    private companion object {
        const val VIEWPORT = "queue-viewport"
        const val ABOVE = "above the rows"
        const val ROWS = 8
        const val DENSITY = 3f
        const val WIDTH_DP = 360
        const val HEIGHT_DP = 365
    }
}
