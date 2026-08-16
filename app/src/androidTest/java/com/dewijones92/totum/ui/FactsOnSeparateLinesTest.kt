package com.dewijones92.totum.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.mediaItemFacts
import com.dewijones92.totum.ui.player.ViewsAndDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * One fact per line, in a list and on the video page — and no ellipsis able to swallow one.
 *
 * Dewi, 2026-08-15: *"the view count and the date published on YouTube videos sometimes gets
 * hidden, there's like a 3-dot thing. I want them each to be on a separate line … and they need to
 * be also visible within the video page itself."*
 *
 * The three facts used to be joined into `channel · views · date` and rendered at `maxLines = 1`, so
 * on a real phone a long channel name pushed the view count and the date past the right-hand edge
 * and Compose replaced them with an ellipsis. `MediaItemSubtitleTest` covers the ordering and the
 * blank-dropping; only a rendered test can show that they now occupy separate lines and that a long
 * channel name no longer costs you the other two.
 *
 * Asserting with EXACT text, deliberately: a joined line would still match `substring = true`, which
 * is how the old scrolled-row test kept passing while half its subject was invisible.
 */
class FactsOnSeparateLinesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun video(author: String = CHANNEL) = MediaItem(
        id = MediaItemId("ytZiDr1NLQc"),
        sourceId = SourceId("youtube"),
        title = "What Will Russia's Fall Offensive Look Like?",
        publishedAt = null,
        publishedText = DATE,
        duration = 24.minutes,
        author = author,
        thumbnailUrl = HttpUrl.of("https://example.test/thumb.jpg"),
        viewsText = VIEWS,
    )

    private fun row(item: MediaItem = video()) {
        compose.setContent {
            TotumTheme {
                LazyColumn {
                    item {
                        MediaItemRow(
                            item = item,
                            subtitleLines = mediaItemFacts(item),
                            pillar = MediaKind.VIDEO,
                            onPlay = {},
                            onDownload = {},
                            onDeleteDownload = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun eachFactIsItsOwnLineInAList() {
        row()

        // An exact match can only succeed if the fact is alone in its node.
        compose.onNodeWithText(CHANNEL).assertIsDisplayed()
        compose.onNodeWithText(VIEWS).assertIsDisplayed()
        compose.onNodeWithText(DATE).assertIsDisplayed()
    }

    /**
     * Stacked, not wrapped: each sits below the one before it, in reading order.
     *
     * On the UNMERGED tree, because a row is clickable and Compose merges its descendants — the
     * merged node's bounds are the row's, so every fact reported the same position and the
     * assertion compared 0.0 with 0.0.
     */
    @Test
    fun theFactsAreStackedInReadingOrder() {
        row()

        val channelTop = topOf(CHANNEL)
        val viewsTop = topOf(VIEWS)
        val dateTop = topOf(DATE)

        assertTrue("views should sit below the channel ($channelTop then $viewsTop)", viewsTop > channelTop)
        assertTrue("the date should sit below the views ($viewsTop then $dateTop)", dateTop > viewsTop)
    }

    /**
     * THE reported bug. A channel name long enough to fill the row used to take the view count and
     * the date with it; now it can only ever shorten itself.
     */
    @Test
    fun aVeryLongChannelNameNoLongerHidesTheViewCountOrTheDate() {
        row(video(author = "A Channel With An Extremely Long Name That Fills The Whole Row And Then Some More"))

        compose.onNodeWithText(VIEWS).assertIsDisplayed()
        compose.onNodeWithText(DATE).assertIsDisplayed()
    }

    /** The other half of what he asked for: the same treatment on the video page. */
    @Test
    fun theVideoPageShowsThemOnSeparateLinesToo() {
        compose.setContent { TotumTheme { ViewsAndDate(playing()) } }

        compose.onNodeWithText(VIEWS).assertIsDisplayed()
        compose.onNodeWithText(DATE).assertIsDisplayed()
    }

    @Test
    fun theVideoPageStacksThemToo() {
        compose.setContent { TotumTheme { ViewsAndDate(playing()) } }

        val viewsTop = topOf(VIEWS)
        val dateTop = topOf(DATE)

        assertTrue("the date should sit below the views ($viewsTop then $dateTop)", dateTop > viewsTop)
    }

    private fun topOf(text: String): Float =
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun playing() = PlaybackState(
        itemId = MediaItemId("ytZiDr1NLQc"),
        title = "What Will Russia's Fall Offensive Look Like?",
        artist = CHANNEL,
        artworkUrl = null,
        viewsText = VIEWS,
        publishedText = DATE,
        isPlaying = true,
        positionMs = 0,
        durationMs = 1_447_200,
        speed = 1f,
    )

    private companion object {
        const val CHANNEL = "WarFronts"
        const val VIEWS = "1.2M views"
        const val DATE = "2 days ago"
    }
}
