package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.group.FakeSourceGroupStore
import com.dewijones92.totum.data.group.GroupFeed
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceGroupId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.SHOW_FEED_TAG
import org.junit.Rule
import org.junit.Test

class VideosHiddenUntilAskedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun text(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val channelUrl = HttpUrl.of("https://www.youtube.com/channel/UCfast")
    private val channel = MediaSource.VideoChannel(SourceId(channelUrl.value), "Fast channel", channelUrl)
    private val group = SourceGroup(SourceGroupId("fast"), "Fasting group", listOf(channel))
    private val video = MediaItem(
        id = MediaItemId("fast-video"),
        sourceId = channel.id,
        title = "A tempting new video",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=fast"),
    )

    private fun container() = FakeAppContainer(
        sourceGroupStore = FakeSourceGroupStore(listOf(group)),
        groupFeed = GroupFeed { listOf(video) },
    )

    private fun showShell(container: FakeAppContainer) {
        composeTestRule.setContent { TotumTheme { AppShell(container) } }
        composeTestRule.onNodeWithText(group.name).performClick()
        composeTestRule.waitForIdle()
    }

    private fun videoRows() = composeTestRule.onAllNodesWithText(video.title)

    @Test
    fun theFeedStaysHiddenUntilShowVideosIsPressed() {
        showShell(container())

        composeTestRule.onNodeWithTag(SHOW_FEED_TAG).assertIsDisplayed()
        videoRows().assertCountEquals(0)

        composeTestRule.onNodeWithTag(SHOW_FEED_TAG).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { videoRows().fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onAllNodesWithTag(SHOW_FEED_TAG).assertCountEquals(0)
    }

    @Test
    fun leavingTheTabHidesTheFeedAgain() {
        showShell(container())
        composeTestRule.onNodeWithTag(SHOW_FEED_TAG).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { videoRows().fetchSemanticsNodes().isNotEmpty() }

        composeTestRule.onNodeWithText(text(R.string.destination_podcasts)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(text(R.string.destination_videos)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SHOW_FEED_TAG).assertIsDisplayed()
        videoRows().assertCountEquals(0)
    }

    @Test
    fun withTheSettingOffTheFeedShowsStraightAway() {
        val container = container()
        container.appPreferences.setFeedHiddenUntilAsked(false)
        showShell(container)

        composeTestRule.waitUntil(TIMEOUT_MS) { videoRows().fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onAllNodesWithTag(SHOW_FEED_TAG).assertCountEquals(0)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
