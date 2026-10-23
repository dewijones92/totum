package com.dewijones92.totum.stars

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.actions.fake.FakeYouTubeActions
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.ui.playlist.LocalPlaylistDetailScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StarAnythingTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val actions = FakeYouTubeActions()

    private val episode = MediaItem(
        id = MediaItemId("https://feeds.example/ep-12.mp3"),
        sourceId = SourceId("https://feeds.example/rss"),
        title = "Episode twelve",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://feeds.example/ep-12.mp3"),
    )

    @Test
    fun aPodcastEpisodeStarredFromItsMenuIsOnTheStarredPageWithNoYouTubeCall() {
        val container = FakeAppContainer(youTubeActions = actions)
        rule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) {
                    MediaItemRow(item = episode, subtitleLines = emptyList(), pillar = MediaKind.PODCAST, onPlay = {})
                }
            }
        }

        rule.onNodeWithContentDescription(context.getString(R.string.queue_menu)).performClick()
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodes(hasText(context.getString(R.string.star))).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(context.getString(R.string.star)).performClick()
        rule.waitUntil(TIMEOUT_MS) { runBlocking { episode.id in container.stars.starredIds.first() } }

        assertEquals(emptyList<Any>(), actions.ratingCalls)
        assertTrue(
            runBlocking { container.localPlaylistStore.observePlaylists().first() }.any { it.id == STARRED_PLAYLIST }
        )
    }

    @Test
    fun theStarredPageListsWhatWasStarred() {
        val container = FakeAppContainer(youTubeActions = actions)
        runBlocking {
            container.stars.markHere(
                com.dewijones92.totum.domain.PlayableItem(episode, com.dewijones92.totum.domain.PlayHandle.Podcast()),
                true
            )
        }
        rule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) {
                    LocalPlaylistDetailScreen(container, STARRED_PLAYLIST, onBack = {})
                }
            }
        }

        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasText("Episode twelve")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(
            TIMEOUT_MS
        ) { rule.onAllNodes(hasText(STARRED_PLAYLIST_NAME)).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
