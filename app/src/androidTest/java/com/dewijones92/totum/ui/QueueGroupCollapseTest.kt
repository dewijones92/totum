package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.dewijones92.totum.ui.queue.queueGroupHeaderTag
import org.junit.Rule
import org.junit.Test

/**
 * A queued season folds away to one line.
 *
 * Dewi, 2026-08-02: *"make the group things in the queue tab collapseable"*. Queueing a whole
 * series drops two dozen rows into the queue under one header, which is exactly the feature
 * working and also what makes everything below it unreachable without a long scroll.
 *
 * Instrumented because the thing under test is what is on screen: rows present, rows gone, and a
 * header that still says what it is hiding.
 */
class QueueGroupCollapseTest {

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

    private val season = QueueGroup(id = "season-1", title = "Peep Show S01")

    /**
     * Toggles by tag, which is the only reliable handle on it.
     *
     * Finding the header by its title does not work: merged semantics combine TEXT but not
     * ACTIONS, so the node carrying "Peep Show S01" has no click to perform.
     */
    private fun toggleSeason() = composeTestRule
        // Only the group's first header is tagged, so this is unambiguous even when the queue
        // has split the season into separate runs — which this one has.
        .onNodeWithTag(queueGroupHeaderTag(season.id))
        .performClick()

    private fun show() {
        val container = FakeAppContainer()
        // Queued the way a season actually arrives — one "play all" with a group — so the test
        // exercises the same path the feature does rather than a hand-built snapshot.
        container.playbackQueue.playAll(
            listOf(playable("S01E01"), playable("S01E02"), playable("S01E03")),
            group = season,
        )
        container.playbackQueue.playAll(listOf(playable("something-else")))
        composeTestRule.setContent { TotumTheme { QueueScreen(container) } }
    }

    @Test
    fun aGroupsRowsAreVisibleUntilItIsCollapsed() {
        show()

        composeTestRule.onNodeWithText("S01E01").assertIsDisplayed()
        composeTestRule.onNodeWithText("S01E03").assertIsDisplayed()
    }

    @Test
    fun tappingTheHeaderHidesTheGroupsRows() {
        show()

        toggleSeason()

        composeTestRule.onNodeWithText("S01E01").assertDoesNotExist()
        composeTestRule.onNodeWithText("S01E03").assertDoesNotExist()
    }

    /** Collapsing one run must not take the rest of the queue with it. */
    @Test
    fun itemsOutsideTheGroupSurviveCollapsing() {
        show()

        toggleSeason()

        composeTestRule.onNodeWithText("something-else").assertIsDisplayed()
    }

    /**
     * A collapsed run says what it is hiding, and that the thing playing is inside it — otherwise
     * collapsing would make the current item vanish with no explanation.
     */
    @Test
    fun aCollapsedGroupSaysWhatItIsHiding() {
        show()

        toggleSeason()

        // Plain "3 items hidden" here: the second playAll moves the cursor onto the item
        // OUTSIDE the season, so this run does not contain what is playing. The "· playing"
        // variant exists for when it does, which is the case that stops a collapse hiding the
        // current item with no explanation.
        // ONE line, not one per run: this queue splits the season (playAll inserts the second
        // batch after the current item), and three identical "3 items hidden" lines would be
        // three claims about the same three items.
        composeTestRule.onNodeWithText("🙈 3 items hidden").assertIsDisplayed()
    }

    @Test
    fun tappingAgainBringsTheRowsBack() {
        show()

        toggleSeason()
        toggleSeason()

        composeTestRule.onNodeWithText("S01E02").assertIsDisplayed()
    }
}
