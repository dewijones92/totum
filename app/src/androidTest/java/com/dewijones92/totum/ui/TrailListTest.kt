package com.dewijones92.totum.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.queue.PlayTrail
import com.dewijones92.totum.queue.TrailNavigation
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.ui.player.LocalCompactTiles
import com.dewijones92.totum.ui.player.LocalTrail
import com.dewijones92.totum.ui.player.TRAIL_BACK_TAG
import com.dewijones92.totum.ui.player.TRAIL_SHEET_TAG
import com.dewijones92.totum.ui.player.TrailTiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrailListTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun playable(id: String) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("s"),
            "Title of $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private val container = FakeAppContainer()
    private val trail = PlayTrail().apply { listOf("one", "two", "three").forEach { noted(playable(it)) } }
    private val navigation =
        TrailNavigation(trail, container.playbackQueue, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private fun show() {
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) {
                    CompositionLocalProvider(LocalTrail provides navigation, LocalCompactTiles provides true) {
                        TrailTiles()
                    }
                }
            }
        }
    }

    @Test
    fun holdingBackListsTheWholeTrailAndMarksWhereYouAre() {
        show()

        composeTestRule.onNodeWithTag(TRAIL_BACK_TAG).performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TRAIL_SHEET_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.trail_hint)).assertIsDisplayed()
        listOf("one", "two", "three").forEach { composeTestRule.onNodeWithText("Title of $it").assertIsDisplayed() }
        composeTestRule.onNodeWithText(context.getString(R.string.trail_here), substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingARowJumpsThereAndKeepsWhatIsAhead() {
        show()
        composeTestRule.onNodeWithTag(TRAIL_BACK_TAG).performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Title of one").performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { container.playbackQueue.nowPlaying.value?.item?.id?.value == "one" }
        trail.noted(playable("one"))

        assertEquals(listOf("one", "two", "three"), trail.snapshot().first.map { it.item.id.value })
        assertEquals(0, trail.snapshot().second)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
