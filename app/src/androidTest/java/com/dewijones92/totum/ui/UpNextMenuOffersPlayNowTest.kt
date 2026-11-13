package com.dewijones92.totum.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
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
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.ui.player.UpNextSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpNextMenuOffersPlayNowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val container = FakeAppContainer()

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

    private fun label(res: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(res)

    @Test
    fun anUpNextRowMenuOffersPlayNowThenBackToWhatIsPlaying() {
        container.playbackQueue.playAll(listOf(playable("first"), playable("second"), playable("third")))
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) {
                    val queue by container.playbackQueue.state.collectAsState()
                    UpNextSection(upNextControls(container.playbackQueue, queue.upNext))
                }
            }
        }

        composeTestRule.onNodeWithText("second").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(label(R.string.queue_play_instead)).assertExists()
    }
}
