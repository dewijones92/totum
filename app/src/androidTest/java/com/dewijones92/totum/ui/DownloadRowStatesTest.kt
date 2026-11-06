package com.dewijones92.totum.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.library.LibraryViewModel
import com.dewijones92.totum.ui.library.failedSection
import com.dewijones92.totum.ui.library.runningSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two download rows that could not be acted on, and one that could not even be read.
 *
 * No commas in the test names: dex cannot represent one in a method name and D8 fails the WHOLE
 * androidTest build with "cannot be represented in dex format" — the same trap as an apostrophe,
 * which this repo has now hit twice.
 *
 * Dewi, 2026-08-07: *"e.g. cancel inprogress download"*. Three separate gaps meet here:
 *
 * - a download in flight had **no way to be stopped** — once started it ran to completion whatever
 *   you did, which on a phone is minutes and hundreds of megabytes for a video started by accident;
 * - it showed the raw media id rather than a title, because the progress stream carried states with
 *   no items attached, so a downloading video appeared as `chxbS3N3Llc`;
 * - a **failed** download vanished from the UI entirely while its row sat in the database, so an
 *   episode that never arrived came with no explanation and no way to try again.
 *
 * Driven as list sections rather than through the whole Library screen: these are three row states,
 * and the screen would need a view model, a queue and a download manager to assert something none of
 * them take part in.
 */
@RunWith(AndroidJUnit4::class)
class DownloadRowStatesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun item(title: String) = MediaItem(
        id = MediaItemId("item-1"),
        sourceId = SourceId("feed"),
        title = title,
        publishedAt = null,
        duration = null,
    )

    private val cancelled = mutableListOf<MediaItemId>()
    private val retried = mutableListOf<MediaItemId>()
    private val dismissed = mutableListOf<MediaItemId>()
    private var cancelledAll = 0

    private fun showRunning(vararg titles: String) {
        val rows = titles.mapIndexed { index, title ->
            LibraryViewModel.InProgress(
                item(title).copy(id = MediaItemId("item-$index")),
                DownloadState.Downloading(index * TENTH, HUNDRED),
            )
        }
        composeTestRule.setContent {
            TotumTheme {
                LazyColumn(modifier = Modifier.testTag("list")) {
                    runningSection(rows, onCancel = { cancelled += it }, onCancelAll = { cancelledAll++ })
                }
            }
        }
    }

    private fun showFailed(title: String, reason: String) {
        composeTestRule.setContent {
            TotumTheme {
                LazyColumn(modifier = Modifier.testTag("list")) {
                    failedSection(
                        listOf(LibraryViewModel.Failed(item(title), reason)),
                        onRetry = { retried += it },
                        onDismiss = { dismissed += it },
                    )
                }
            }
        }
    }

    /** It printed the raw media id, which tells you nothing about what is downloading. */
    @Test
    fun `a download in flight is named rather than identified by its media id`() {
        showRunning("Is this Gary Stevensons last EVER interview")

        composeTestRule.onNodeWithText("Gary Stevensons", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a download in flight can be stopped`() {
        showRunning("An episode")

        composeTestRule.onNodeWithContentDescription("Cancel download", substring = true).performClick()

        assertEquals(listOf(MediaItemId("item-0")), cancelled)
    }

    /**
     * Cancel-all appears only when there is more than one.
     *
     * With a single download running it is the same action as the button already on the row, and an
     * "all" that means "the one" is just a second way to do the same thing in a more alarming word.
     */
    @Test
    fun `cancel all is offered only when more than one is running`() {
        showRunning("Just the one")

        assertEquals(
            0,
            composeTestRule.onAllNodesWithTextSafely("Cancel all").size,
        )
    }

    @Test
    fun `cancel all appears and works with several running`() {
        showRunning("First", "Second")

        composeTestRule.onNodeWithText("Cancel all", substring = true).performClick()

        assertEquals(1, cancelledAll)
    }

    /** A failure must say what happened; "download failed" would hide the only useful part. */
    @Test
    fun `a failed download shows its reason`() {
        showFailed("An episode", "This video is available to members")

        composeTestRule.onNodeWithText("An episode", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("available to members", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a failed download can be retried`() {
        showFailed("An episode", "no space")

        composeTestRule.onNodeWithText("Try again", substring = true).performClick()

        assertEquals(listOf(MediaItemId("item-1")), retried)
    }

    @Test
    fun `a failed download can be dismissed instead`() {
        showFailed("An episode", "no space")

        composeTestRule.onNodeWithText("Remove from list", substring = true).performClick()

        assertEquals(listOf(MediaItemId("item-1")), dismissed)
    }

    /** `onAllNodesWithText(...).fetchSemanticsNodes()` reads better wrapped than inline. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafely(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes()

    private companion object {
        const val HUNDRED = 100L
        const val TENTH = 10L
    }
}
