package com.dewijones92.totum.downloads

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.torrent.TorrentFileDetail
import com.dewijones92.totum.data.torrent.TorrentFileRole
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.DownloadChoice
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.EPISODE_EXTRAS_TAG
import com.dewijones92.totum.ui.common.EPISODE_LIST_TAG
import com.dewijones92.totum.ui.common.EpisodePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EpisodePickerTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun video(id: String, title: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = title,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    private fun reveal(row: SemanticsMatcher) {
        rule.onNodeWithTag(EPISODE_LIST_TAG).performScrollToNode(row)
    }

    @Test
    fun everyEpisodeStartsTickedAndOnlyTheTickedOnesDownload() {
        val downloads = FakeDownloadManager()
        val episodes = EpisodeDownloads(downloads, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        rule.setContent { TotumTheme { EpisodePicker(episodes, downloads, DownloadChoice.Video(720)) } }
        rule.runOnIdle {
            episodes.ask(
                "Peppa",
                listOf(
                    video("aaaaaaaaaaa", "Muddy Puddles"),
                    video("bbbbbbbbbbb", "Mr Dinosaur"),
                    video("ccccccccccc", "Best Friend")
                ),
            )
        }

        reveal(hasText("Mr Dinosaur", substring = true))
        rule.onNodeWithText("Mr Dinosaur", substring = true).performClick()
        rule.onNodeWithText(context.getString(R.string.episodes_download, 2)).performClick()

        rule.waitUntil(TIMEOUT_MS) { downloads.requested.size == 2 }
        assertEquals(listOf("aaaaaaaaaaa", "ccccccccccc"), downloads.requested.map { it.first.value })
        assertEquals(listOf(720, 720), downloads.requestedHeights)
    }

    @Test
    fun anEpisodeAlreadyOnThePhoneStartsUnticked() {
        val downloads = FakeDownloadManager()
        val episodes = EpisodeDownloads(downloads, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val kept = video("aaaaaaaaaaa", "Muddy Puddles")
        kotlinx.coroutines.runBlocking { downloads.download(kept, audioOnly = false) }
        rule.setContent { TotumTheme { EpisodePicker(episodes, downloads, DownloadChoice.Video(null)) } }
        rule.runOnIdle { episodes.ask("Peppa", listOf(kept, video("bbbbbbbbbbb", "Mr Dinosaur"))) }

        rule.onNodeWithText(context.getString(R.string.episodes_download, 1)).performClick()

        rule.waitUntil(TIMEOUT_MS) { downloads.requested.size == 2 }
        assertEquals("bbbbbbbbbbb", downloads.requested.last().first.value)
    }

    @Test
    fun extrasStartUntickedUnderAGroupThatOpensOnRequest() {
        val downloads = FakeDownloadManager()
        val episodes = EpisodeDownloads(downloads, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        rule.setContent { TotumTheme { EpisodePicker(episodes, downloads, DownloadChoice.Video(720)) } }
        val film = video("aaaaaaaaaaa", "Spinal Tap II")
        val featurette = video("bbbbbbbbbbb", "History of the Group")
        rule.runOnIdle {
            episodes.ask(
                "Spinal Tap II",
                listOf(film, featurette),
                mapOf(
                    film.item.id to TorrentFileDetail(5_500_000_000, TorrentFileRole.MAIN),
                    featurette.item.id to TorrentFileDetail(107_843_601, TorrentFileRole.EXTRA),
                ),
            )
        }

        reveal(hasText("Spinal Tap II", substring = true))
        rule.onAllNodesWithText("Spinal Tap II", substring = true).assertCountEquals(2)
        reveal(hasTestTag(EPISODE_EXTRAS_TAG))
        rule.onNodeWithText("History of the Group", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(EPISODE_EXTRAS_TAG).performClick()
        reveal(hasText("History of the Group", substring = true))
        rule.onNodeWithText("History of the Group", substring = true).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.episodes_download, 1)).performClick()

        rule.waitUntil(TIMEOUT_MS) { downloads.requested.size == 1 }
        assertEquals("aaaaaaaaaaa", downloads.requested.single().first.value)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
