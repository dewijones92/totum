package com.dewijones92.totum.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.FollowedSources
import com.dewijones92.totum.domain.Following
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.UnknownBecause
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.FOLLOW_MARK_TAG
import com.dewijones92.totum.ui.common.FollowMarkSpec
import com.dewijones92.totum.ui.common.LocalRowFollowing
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.RowFollowing
import com.dewijones92.totum.ui.common.mediaItemFacts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FollowMarkTest {

    @get:Rule
    val compose = createComposeRule()

    private val channel = MediaSource.VideoChannel(SourceId(CHANNEL_URL), "Novara Media", HttpUrl.of(CHANNEL_URL))
    private val feed = MediaSource.PodcastFeed(SourceId(FEED_URL), "Football Daily", HttpUrl.of(FEED_URL))
    private val calls = mutableListOf<Pair<Following, Boolean>>()

    private val video = MediaItem(
        id = MediaItemId("v1"),
        sourceId = SourceId("ytfeed:SUBSCRIPTIONS"),
        title = "Riot Police Clash With Protesters",
        publishedAt = null,
        duration = null,
        author = "Novara Media",
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=v1"),
        sourceUrl = HttpUrl.of(CHANNEL_URL),
    )
    private val episode = MediaItem(
        id = MediaItemId("e1"),
        sourceId = SourceId(FEED_URL),
        title = "Arsenal win again",
        publishedAt = null,
        duration = null,
        author = "Football Daily",
        mediaUrl = HttpUrl.of("https://podcasts.example.com/e1.mp3"),
    )

    private fun row(item: MediaItem, pillar: MediaKind, following: Following?) {
        compose.setContent {
            TotumTheme {
                LazyColumn {
                    item {
                        MediaItemRow(
                            item = item,
                            subtitleLines = mediaItemFacts(item, pillar),
                            pillar = pillar,
                            onPlay = {},
                            onDownload = {},
                            onDeleteDownload = {},
                            followMark = following?.let { FollowMarkSpec(it) { answer, on -> calls += answer to on } },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aSubscribedChannelSaysSoBesideItsName() {
        row(video, MediaKind.VIDEO, Following.Subscribed(channel))

        compose.onNodeWithText("📺 Novara Media").assertIsDisplayed()
        compose.onNodeWithText("✅ subscribed").assertIsDisplayed()
    }

    @Test
    fun tappingNotSubscribedSubscribesAtOnce() {
        row(video, MediaKind.VIDEO, Following.NotSubscribed(channel))

        compose.onNodeWithText("➕ not subscribed").performClick()

        assertEquals(listOf<Pair<Following, Boolean>>(Following.NotSubscribed(channel) to true), calls)
    }

    @Test
    fun tappingSubscribedAsksBeforeUnsubscribing() {
        row(video, MediaKind.VIDEO, Following.Subscribed(channel))

        compose.onNodeWithText("✅ subscribed").performClick()
        assertEquals(emptyList<Any>(), calls)
        compose.onNodeWithText("Unsubscribe from Novara Media?").assertIsDisplayed()
        compose.onNodeWithText("Unsubscribe").performClick()

        assertEquals(listOf<Pair<Following, Boolean>>(Following.Subscribed(channel) to false), calls)
    }

    @Test
    fun keepingAtTheQuestionChangesNothing() {
        row(video, MediaKind.VIDEO, Following.Subscribed(channel))

        compose.onNodeWithText("✅ subscribed").performClick()
        compose.onNodeWithText("Keep").performClick()

        assertEquals(emptyList<Any>(), calls)
        compose.onNodeWithText("Unsubscribe from Novara Media?").assertDoesNotExist()
    }

    @Test
    fun unknownShowsAQuestionMarkAndTappingItDoesNothing() {
        row(video, MediaKind.VIDEO, Following.Unknown(UnknownBecause.SIGNED_OUT))

        compose.onNodeWithText("❔ unknown").performClick()

        assertEquals(emptyList<Any>(), calls)
    }

    @Test
    fun aPodcastEpisodeIsMarkedTheSameWay() {
        row(episode, MediaKind.PODCAST, Following.NotSubscribed(feed))

        compose.onNodeWithText("🎙️ Football Daily").assertIsDisplayed()
        compose.onNodeWithText("➕ not subscribed").performClick()

        assertEquals(listOf<Pair<Following, Boolean>>(Following.NotSubscribed(feed) to true), calls)
    }

    @Test
    fun aRowKnownOnlyByItsChannelNameOffersToSubscribe() {
        val nameOnly = video.copy(sourceUrl = null, author = "Middle East Eye")
        row(nameOnly, MediaKind.VIDEO, Following.NotSubscribedByName(nameOnly))

        compose.onNodeWithText("➕ not subscribed").performClick()

        assertEquals(listOf<Pair<Following, Boolean>>(Following.NotSubscribedByName(nameOnly) to true), calls)
    }

    @Test
    fun aRowWithNothingProvidedDrawsNoMark() {
        row(video, MediaKind.VIDEO, following = null)

        compose.onNodeWithTag(FOLLOW_MARK_TAG).assertDoesNotExist()
    }

    @Test
    fun theProvidedSubscriptionsDecideEveryRowAndFollowAChange() {
        var followed by mutableStateOf(FollowedSources(emptyList(), emptyList(), signedIn = true))
        compose.setContent {
            TotumTheme {
                CompositionLocalProvider(
                    LocalRowFollowing provides RowFollowing(followed) { answer, on -> calls += answer to on },
                ) {
                    LazyColumn {
                        item {
                            MediaItemRow(video, mediaItemFacts(video, MediaKind.VIDEO), MediaKind.VIDEO, onPlay = {})
                        }
                        item {
                            MediaItemRow(
                                episode,
                                mediaItemFacts(episode, MediaKind.PODCAST),
                                MediaKind.PODCAST,
                                onPlay = {}
                            )
                        }
                    }
                }
            }
        }
        assertEquals(2, count("➕ not subscribed"))

        followed = FollowedSources(listOf(channel), listOf(feed), signedIn = true)
        compose.waitForIdle()

        assertEquals(2, count("✅ subscribed"))
        assertEquals(0, count("➕ not subscribed"))
    }

    private fun count(text: String): Int = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private companion object {
        const val CHANNEL_URL = "https://www.youtube.com/channel/UCnovara"
        const val FEED_URL = "https://podcasts.example.com/football-daily.xml"
    }
}
