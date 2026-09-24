package com.dewijones92.totum.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceActivity
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.subscriptions.AllSubscriptionsContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AllSubscriptionsListTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val show = MediaSource.PodcastFeed(
        SourceId("https://feeds.example.com/show.rss"),
        "The Show",
        HttpUrl.of("https://feeds.example.com/show.rss"),
    )
    private val channel = MediaSource.VideoChannel(
        SourceId("channel"),
        "A Channel",
        HttpUrl.of("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"),
    )
    private val latest = MediaItem(
        id = MediaItemId("vid"),
        sourceId = channel.id,
        title = "The newest upload",
        publishedAt = Instant.now().minusSeconds(7200),
        duration = null,
    )

    @Test
    fun `every subscription of both pillars is listed and tapping one opens it`() {
        var opened: MediaSource? = null
        composeTestRule.setContent {
            TotumTheme {
                AllSubscriptionsContent(
                    sources = listOf(SourceActivity(channel, latest), SourceActivity(show, null)),
                    onBack = {},
                    onOpen = { opened = it.source },
                )
            }
        }
        val noRecent = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.all_subscriptions_no_recent)

        composeTestRule.onNodeWithText("A Channel").assertExists()
        composeTestRule.onAllNodesWithText("The newest upload", substring = true).fetchSemanticsNodes().single()
        composeTestRule.onNodeWithText(noRecent, substring = true).assertExists()
        composeTestRule.onNodeWithText("The Show").performClick()

        assertEquals(show, opened)
    }
}
