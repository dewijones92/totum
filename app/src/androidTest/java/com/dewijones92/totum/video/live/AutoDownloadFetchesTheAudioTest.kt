package com.dewijones92.totum.video.live

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Queue something and the app fetches its AUDIO by itself, with nobody tapping download.
 *
 * `QueueAutoDownloader` is what makes the queue listenable on a plane, and it is the feature behind
 * *"also it should have downloaded the audio???"* (0.1.435, a Ms Rachel video 52 minutes in, first in a
 * 47-item queue, with no download event at all). Its LOGIC has unit tests. What had no test at all is
 * the thing that actually has to happen on a device: put an item in the queue, touch nothing else, and
 * find an audio file on disk afterwards.
 *
 * That gap matters because every part of this runs somewhere different — the watcher on an application
 * scope, the network gate, the strategy routing by pillar, the store — and a unit test of the loop
 * cannot see any of them disagree.
 *
 * Asserts audio-ONLY, not merely "a file appeared": fetching the whole video would also produce a file,
 * cost many times the data, and be the exact bug `fetchesAudioOnly` exists to prevent.
 */
class AutoDownloadFetchesTheAudioTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as TotumApplication
    private val container get() = app.container
    private val id = MediaItemId(VIDEO_ID)

    @Before
    fun startFromNothingDownloaded() = runBlocking {
        container.playbackQueue.clear()
        (listOf(VIDEO_ID) + SEVERAL).forEach {
            container.downloadManager.cancel(MediaItemId(it))
            container.downloadManager.delete(MediaItemId(it))
        }
        container.appPreferences.setAutoDownloadQueue(true)
        // Wi-Fi-only would gate the whole thing off on a metered emulator, and that is a different
        // question from "does the automatic fetch happen at all".
        container.appPreferences.setAutoDownloadWifiOnly(false)
    }

    @After
    fun leaveNothingBehind() = runBlocking {
        (listOf(VIDEO_ID) + SEVERAL).forEach {
            container.downloadManager.cancel(MediaItemId(it))
            container.downloadManager.delete(MediaItemId(it))
        }
        container.playbackQueue.clear()
    }

    @Test
    fun queueingAVideoFetchesItsAudioWithoutBeingAsked() = runBlocking {
        container.startQueueAutoDownload()
        container.playbackQueue.playNext(video())

        val downloaded = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            container.downloadManager.observe(id).first { it is DownloadState.Downloaded }
        } as DownloadState.Downloaded?

        Log.i("dewidebug", "auto-download outcome=$downloaded")
        assertTrue(
            "nothing was fetched for a queued video in ${DOWNLOAD_TIMEOUT_MS / MS_PER_SECOND}s, so the " +
                "queue is not listenable offline — see the [download] lines in logcat for the reason",
            downloaded != null,
        )
        assertTrue(
            "the automatic fetch pulled the WHOLE video, not just its audio: ${downloaded!!.localPath}",
            downloaded.audioOnly,
        )
        val file = File(downloaded.localPath)
        assertTrue("the download reported a path that is not a real file: ${downloaded.localPath}", file.isFile)
        assertTrue("the fetched audio is suspiciously small (${file.length()}B)", file.length() > MIN_BYTES)
    }

    /**
     * Several at once, which is what makes a long queue usable.
     *
     * One at a time was the old shape and it is far too slow for the queues this is actually for:
     * Dewi's is regularly 80 items, and each fetch begins with a yt-dlp resolve that spends most of
     * its time waiting on YouTube rather than moving bytes. Dewi, 2026-08-31: *"background
     * downloading should work for multiple files in parallel"*.
     *
     * The unit tests prove the scheduler hands out lanes. Only this can prove the real graph acts on
     * it — the manager launching into its own scope, the store recording three rows, the strategies
     * running side by side — and the failure mode if it does not is invisible: everything still
     * downloads, just one at a time, exactly as before.
     */
    @Test
    fun severalQueuedItemsAreFetchedAtOnce() = runBlocking {
        container.startQueueAutoDownload()
        SEVERAL.forEach { container.playbackQueue.playNext(video(it)) }

        var mostAtOnce = 0
        withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            // SAMPLED on a clock rather than taken from collected values alone: two downloads
            // starting microseconds apart can produce a stream of snapshots that never happens to
            // show both, and "I did not observe it" would then read as "it did not happen".
            while (mostAtOnce < SEVERAL.size) {
                val running = container.downloadManager.observeDownloads().first()
                    .count { it.value is DownloadState.Downloading }
                mostAtOnce = maxOf(mostAtOnce, running)
                delay(SAMPLE_MS)
            }
        }

        Log.i("dewidebug", "most downloads in flight at once=$mostAtOnce of ${SEVERAL.size} queued")
        assertTrue(
            "only $mostAtOnce download was ever in flight for ${SEVERAL.size} queued items, so the " +
                "queue still drains one at a time — see the [download] pass lines in logcat",
            mostAtOnce > 1,
        )
    }

    private fun video(videoId: String = VIDEO_ID) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(videoId),
            sourceId = SourceId("auto-download-test"),
            title = "a queued video whose audio should arrive by itself",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$videoId"),
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$videoId")),
    )

    private companion object {
        /** "Me at the zoo" — 19 seconds, so the whole fetch is quick and cheap. */
        const val VIDEO_ID = "jNQXAC9IVRw"
        const val WATCH = "https://www.youtube.com/watch?v=$VIDEO_ID"

        /** Three known-good videos, so the lanes have something real to race over. */
        val SEVERAL = listOf("jNQXAC9IVRw", "aqz-KE-bpKQ", "ttiLcMUQq80")

        const val SAMPLE_MS = 50L

        const val DOWNLOAD_TIMEOUT_MS = 240_000L
        const val MS_PER_SECOND = 1_000L

        /** Bigger than an error page, smaller than any real 19-second audio track. */
        const val MIN_BYTES = 20_000L
    }
}
