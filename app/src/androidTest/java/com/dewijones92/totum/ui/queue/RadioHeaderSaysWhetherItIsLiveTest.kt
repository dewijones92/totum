package com.dewijones92.totum.ui.queue

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Rule
import org.junit.Test

class RadioHeaderSaysWhetherItIsLiveTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(groupId: String, liveRadio: Boolean) {
        composeTestRule.setContent {
            TotumTheme {
                GroupHeader(
                    GroupHeaderModel(
                        groupId = groupId,
                        isFirstRun = true,
                        title = "Radio · Christmas",
                        count = 3,
                        collapsed = false,
                        containsNowPlaying = false,
                        draggable = false,
                        liveRadio = liveRadio,
                    ),
                    GroupHeaderActions(
                        onToggle = {},
                        onRemove = {},
                        onMove = {},
                        onShuffle = {},
                        onShuffleAndPlay = {},
                    ),
                    drag = null,
                )
            }
        }
    }

    @Test
    fun theLiveStationSaysEndless() {
        show("radio:RDAMVMlive", liveRadio = true)

        composeTestRule.onNodeWithTag(QUEUE_GROUP_ENDLESS_TAG, useUnmergedTree = true)
            .assertTextContains("♾️ Endless radio", substring = true)
    }

    @Test
    fun aLeftoverRadioBlockSaysItIsNotToppingUp() {
        show("radio:RDAMVMold", liveRadio = false)

        composeTestRule.onNodeWithTag(QUEUE_GROUP_ENDLESS_TAG, useUnmergedTree = true)
            .assertTextContains("📻 Radio · not topping up", substring = true)
    }

    @Test
    fun aDiscoverBlockThatIsNotLiveSaysSoToo() {
        show("radio:discover:UC1", liveRadio = false)

        composeTestRule.onNodeWithTag(QUEUE_GROUP_ENDLESS_TAG, useUnmergedTree = true)
            .assertTextContains("🔭 Discover · not topping up", substring = true)
    }
}
