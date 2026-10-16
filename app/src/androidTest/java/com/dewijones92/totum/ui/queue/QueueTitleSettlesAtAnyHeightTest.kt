package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class QueueTitleSettlesAtAnyHeightTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun queued(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = "Queued $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    @Test
    fun theQueueSettlesWhateverItsHeightWhenItsRowsNearlyFit() {
        val container = FakeAppContainer()
        container.playbackQueue.playAll(listOf(queued("one"), queued("two"), queued("three")))
        val height = mutableIntStateOf(FROM_DP)
        composeTestRule.setContent {
            val h by height
            TotumTheme { Box(Modifier.fillMaxWidth().requiredHeight(h.dp)) { QueueScreen(container) } }
        }
        for (dp in FROM_DP..TO_DP step STEP_DP) {
            try {
                composeTestRule.runOnIdle { height.intValue = dp }
                composeTestRule.waitForIdle()
            } catch (busy: Exception) {
                fail("the queue never settled at ${dp}dp: ${busy.message?.take(LIMIT)}")
            }
        }
    }

    private companion object {
        const val FROM_DP = 300
        const val TO_DP = 900
        const val STEP_DP = 6
        const val LIMIT = 200
    }
}
