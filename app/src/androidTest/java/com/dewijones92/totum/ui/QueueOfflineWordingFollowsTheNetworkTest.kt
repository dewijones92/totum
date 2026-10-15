package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.queue.QueueScreen
import org.junit.Rule
import org.junit.Test

class QueueOfflineWordingFollowsTheNetworkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun playable(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    private val unavailable =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.queue_unavailable_offline)

    @Test
    fun rowsSayTheyWillBeSkippedTheMomentTheNetworkGoesAndStopSayingSoWhenItReturns() {
        val container = FakeAppContainer()
        container.playbackQueue.playAll(listOf(playable("first"), playable("second"), playable("third")))
        composeTestRule.setContent { TotumTheme { QueueScreen(container) } }
        composeTestRule.onAllNodesWithText(unavailable, substring = true).assertCountEquals(0)

        composeTestRule.runOnIdle { container.offlineNow.value = true }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText(unavailable, substring = true).assertCountEquals(3)

        composeTestRule.runOnIdle { container.offlineNow.value = false }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText(unavailable, substring = true).assertCountEquals(0)
    }
}
