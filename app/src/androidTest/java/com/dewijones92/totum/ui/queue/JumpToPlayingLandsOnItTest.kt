package com.dewijones92.totum.ui.queue

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Rule
import org.junit.Test

class JumpToPlayingLandsOnItTest {

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

    @Test
    fun theJumpButtonShowsThePlayingItemBelowManyAddedByYouRuns() {
        val container = FakeAppContainer()
        val queue = container.playbackQueue
        repeat(RUNS) { run ->
            queue.enqueue(playable("mine-$run"))
            queue.enqueueAll(
                listOf(playable("show-$run-a"), playable("show-$run-b")),
                QueueGroup("show-$run", "Show $run")
            )
        }
        queue.enqueueAll((1..TAIL).map { playable("tail-$it") })
        queue.jumpTo(queue.state.value.entries.indexOfFirst { it.item.item.id.value == PLAYING })
        composeTestRule.setContent { TotumTheme { QueueScreen(container) } }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToIndex(0)

        composeTestRule.onNodeWithTag(JUMP_TO_PLAYING_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(PLAYING).assertIsDisplayed()
    }

    private companion object {
        const val RUNS = 20
        const val TAIL = 30
        const val PLAYING = "tail-20"
    }
}
