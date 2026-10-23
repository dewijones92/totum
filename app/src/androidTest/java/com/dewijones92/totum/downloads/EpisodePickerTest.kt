package com.dewijones92.totum.downloads

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.DownloadChoice
import com.dewijones92.totum.theme.TotumTheme
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

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
