package com.dewijones92.totum.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceActivity
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.LocalLiveStates
import com.dewijones92.totum.ui.subscriptions.AllSubscriptionsContent
import com.dewijones92.totum.video.LivePhase
import com.dewijones92.totum.video.LiveState
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

    private val liveUpload = MediaItem(
        id = MediaItemId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        sourceId = channel.id,
        title = "A stream",
        publishedAt = Instant.now().minusSeconds(600),
        duration = null,
    )

    private fun showing(upload: MediaItem, states: Map<String, LiveState> = emptyMap()) {
        composeTestRule.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalLiveStates provides remember { mutableStateOf(states) }) {
                    AllSubscriptionsContent(
                        sources = listOf(SourceActivity(channel, upload)),
                        onBack = {},
                        onOpen = {},
                    )
                }
            }
        }
    }

    private val tagLive get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.tag_live)
    private val tagWasLive get() =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.tag_was_live)

    private fun state(phase: LivePhase) = LiveState("dQw4w9WgXcQ", phase, null, "", 0L, 0L)

    @Test
    fun `a latest upload the registry knows is live says so like every row`() {
        showing(liveUpload, mapOf("dQw4w9WgXcQ" to state(LivePhase.LIVE)))

        composeTestRule.onNodeWithText(tagLive).assertExists()
        composeTestRule.onNodeWithText("A stream", substring = true).assertExists()
    }

    @Test
    fun `a latest upload stored as live says so even before the registry has heard of it`() {
        showing(liveUpload.copy(contentKind = MediaContentKind.LIVE))

        composeTestRule.onNodeWithText(tagLive).assertExists()
    }

    @Test
    fun `a stream that ended says it was live`() {
        showing(liveUpload, mapOf("dQw4w9WgXcQ" to state(LivePhase.ENDED)))

        composeTestRule.onNodeWithText(tagWasLive).assertExists()
    }

    @Test
    fun `an ordinary upload carries no tag`() {
        showing(liveUpload)

        composeTestRule.onAllNodesWithText(tagLive).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(tagWasLive).assertCountEquals(0)
    }
}
