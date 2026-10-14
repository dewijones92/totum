package com.dewijones92.totum.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.queue.QueueScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueGroupMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val container = FakeAppContainer()
    private val queue get() = container.playbackQueue
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

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

    private fun ids() = queue.state.value.entries.map { it.item.item.id.value }

    private fun showGroups() {
        queue.playAll(listOf(playable("now")))
        queue.enqueueAll(listOf("a1", "a2").map(::playable), QueueGroup("album:a", "Album A"))
        queue.enqueueAll(listOf("b1", "b2").map(::playable), QueueGroup("album:b", "Album B"))
        composeTestRule.setContent { TotumTheme { QueueScreen(container) } }
    }

    @Test
    fun theGroupMenuMovesTheWholeGroupToTheTop() {
        showGroups()

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.queue_group_menu, "Album B"))
            .performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_move_to_top)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("b1", "b2", "now", "a1", "a2"), ids())
        assertEquals("now", queue.state.value.current?.item?.item?.id?.value)
    }

    @Test
    fun theGroupMenuPlaysTheGroupNext() {
        showGroups()

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.queue_group_menu, "Album B"))
            .performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_play_next)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("now", "b1", "b2", "a1", "a2"), ids())
    }

    @Test
    fun theShuffleMenuOffersShufflingTheOrderOfGroups() {
        showGroups()

        repeat(TRIES) {
            composeTestRule.onNodeWithContentDescription(context.getString(R.string.queue_shuffle_up_next))
                .performClick()
            composeTestRule.onNodeWithText(context.getString(R.string.queue_shuffle_groups)).performClick()
            composeTestRule.waitForIdle()
        }

        val order = ids()
        assertEquals("now", order.first())
        assertTrue("each group stays together and in order: $order", order.indexOf("a2") == order.indexOf("a1") + 1)
        assertTrue("each group stays together and in order: $order", order.indexOf("b2") == order.indexOf("b1") + 1)
    }

    private companion object {
        const val TRIES = 3
    }
}
