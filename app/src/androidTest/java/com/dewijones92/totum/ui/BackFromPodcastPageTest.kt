package com.dewijones92.totum.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.ui.podcasts.PodcastsScreen
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackFromPodcastPageTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val feedUrl = HttpUrl.of("https://feeds.example.com/show.rss")
    private val feed = MediaSource.PodcastFeed(SourceId(feedUrl.value), "The Show", feedUrl)

    @Test
    fun `back on a podcast page returns to the podcast list instead of leaving the app`() {
        val container = FakeAppContainer(
            podcastRepository = FakePodcastRepository(
                initialSubscriptions = listOf(Subscription(feed, Instant.EPOCH)),
                initialEpisodes = listOf(FakePodcastRepository.sampleEpisode(feed.id)),
            ),
        )
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) { PodcastsScreen(container) }
            }
        }
        val activity = composeTestRule.activity
        val latest = activity.getString(R.string.latest_episodes)
        val unsubscribe = activity.getString(R.string.channel_unsubscribe)

        composeTestRule.onNodeWithText("The Show").performClick()
        composeTestRule.onNodeWithText(unsubscribe).assertExists()

        composeTestRule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()

        assertFalse("back left the app", activity.isFinishing)
        composeTestRule.onNodeWithText(latest).assertExists()
    }
}
