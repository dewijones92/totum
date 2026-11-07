package com.dewijones92.totum.ui.queue

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.Listening
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.ExploreMode
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.ListeningSwitchUi
import com.dewijones92.totum.ui.common.LocalExploreMode
import com.dewijones92.totum.ui.common.LocalListeningSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.dewijones92.totum.playback.R as PlaybackR

class TheQueueHeaderKeepsEveryControlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun queued(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = "Row $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    private val explore = ExploreMode(false, { null }, { false }, { null })
    private val switched = mutableListOf<String>()

    private fun show(container: FakeAppContainer) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalExploreMode provides explore,
                LocalListeningSwitch provides ListeningSwitchUi(Listening.MUSIC) { switched += it },
            ) {
                TotumTheme { QueueScreen(container) }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun filled() = FakeAppContainer().also {
        it.playbackQueue.enqueueAll(listOf(queued("a1"), queued("a2")), QueueGroup("a", "Album A"))
        it.playbackQueue.enqueueAll(listOf(queued("b1"), queued("b2")), QueueGroup("b", "Album B"))
        it.playbackQueue.jumpTo(0)
    }

    private fun openMenu() {
        composeTestRule.onNodeWithTag(QUEUE_HEADER_MENU_TAG).performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun theFrequentControlsStayOnTheHeaderAndTheOthersAreInOneMenu() {
        show(filled())

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.queue_jump_to_playing))
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.queue_shuffle_up_next))
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.sort_and_filter)).assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodes(hasContentDescription("Filter", substring = true) and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        )

        openMenu()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_collapse_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(PlaybackR.string.playback_switch_to_music))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.explore_toggle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_clear_all)).assertIsDisplayed()
    }

    @Test
    fun collapseAllAndTheTwoModesWorkFromTheMenu() {
        show(filled())

        openMenu()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_collapse_all)).performClick()
        composeTestRule.waitForIdle()
        openMenu()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_expand_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(PlaybackR.string.playback_switch_to_music)).performClick()
        assertEquals(listOf("queue header"), switched)

        openMenu()
        composeTestRule.onNodeWithText(context.getString(R.string.explore_toggle)).performClick()
        composeTestRule.waitForIdle()
        assertTrue("explore is on after the menu item", explore.on.value)
    }
}
