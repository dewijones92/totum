package com.dewijones92.totum.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomeRetry
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.StorageUsage
import com.dewijones92.totum.downloads.DownloadsSection
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.LocalHomeJobs
import com.dewijones92.totum.ui.library.DownloadSort
import com.dewijones92.totum.ui.library.DownloadsContent
import com.dewijones92.totum.ui.library.HOME_MOVE_TAG
import com.dewijones92.totum.ui.library.HomeSection
import com.dewijones92.totum.ui.library.Sections
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeJobsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun job(id: String, title: String, state: HomeJobState, retry: HomeRetry? = null, error: String? = null) =
        HomeJob(
            id,
            PlayableItem(
                MediaItem(
                    id = MediaItemId(id),
                    sourceId = SourceId("torrent"),
                    title = title,
                    publishedAt = null,
                    duration = null,
                ),
                PlayHandle.Podcast(),
            ).toBackup(),
            state,
            audioOnly = false,
            bytes = 4_000_000_000,
            totalBytes = 5_500_000_000,
            error = error,
            retry = retry,
        )

    @Test
    fun theAtHomeListSaysWhatIsRetryingWhatFailedForGoodAndOffersMoveToPhone() {
        val jobs = listOf(
            job(
                "torrent:aaa:1",
                "Spider-Man: Brand New Day (2026)",
                HomeJobState.WAITING,
                HomeRetry(2, 4, System.currentTimeMillis() + 60_000),
                "timeout",
            ),
            job("torrent:bbb:1", "Frankenstein (2025)", HomeJobState.FAILED, error = "HTTP 404"),
            job("torrent:ccc:1", "Spinal Tap II: The End Continues (2025)", HomeJobState.DONE),
        )
        val known = androidx.compose.runtime.mutableStateOf(jobs.associateBy { MediaItemId(it.id) })
        rule.setContent {
            TotumTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalHomeJobs provides known) {
                DownloadsContent(
                    downloaded = emptyList(),
                    inProgress = emptyList(),
                    failed = emptyList(),
                    storage = StorageUsage(itemCount = 0, usedBytes = 0, freeBytes = null),
                    sort = DownloadSort.DEFAULT,
                    onBack = {},
                    onPlay = {},
                    onDelete = {},
                    onCancel = {},
                    onCancelAll = {},
                    onRetry = {},
                    onDismiss = {},
                    onAddToPlaylist = {},
                    onSetSort = {},
                    sections = Sections(current = DownloadsSection.AT_HOME),
                    home = HomeSection(available = true, jobs = jobs),
                )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("retrying 2 of 4", substring = true).assertIsDisplayed()
        rule.onNodeWithText("failed: HTTP 404", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("$HOME_MOVE_TAG-torrent:ccc:1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.downloads_retry_now)).performScrollTo().assertIsDisplayed()

        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "home-jobs-screen.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
        }
    }

    private companion object {
        const val PNG_QUALITY = 100
    }
}
