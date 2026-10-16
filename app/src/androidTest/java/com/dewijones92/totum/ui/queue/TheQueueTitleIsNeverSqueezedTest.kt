package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.ceil

class TheQueueTitleIsNeverSqueezedTest {

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
    fun theWholeQueueTitleShowsBesideOrAboveItsActionsAtPhoneWidths() {
        val container = FakeAppContainer()
        container.playbackQueue.playAll(listOf(queued("one"), queued("two"), queued("three")))
        val width = mutableIntStateOf(WIDTHS.first())
        composeTestRule.setContent {
            val w by width
            TotumTheme { Box(Modifier.requiredWidth(w.dp)) { QueueScreen(container) } }
        }
        val title = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.queue_title)
        for (dp in WIDTHS) {
            composeTestRule.runOnIdle { width.intValue = dp }
            composeTestRule.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            composeTestRule.onNodeWithText(title).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val needs = ceil(layout.multiParagraph.maxIntrinsicWidth).toInt()
            val given = layout.layoutInput.constraints.maxWidth
            assertTrue("the queue title needs ${needs}px and was given ${given}px at ${dp}dp", given >= needs)
        }
    }

    private companion object {
        val WIDTHS = listOf(320, 360, 411, 600)
    }
}
