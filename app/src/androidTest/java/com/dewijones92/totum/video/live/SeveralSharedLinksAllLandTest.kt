package com.dewijones92.totum.video.live

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Share several links into the app one after another and ALL of them land, each fetched once.
 *
 * Lives under `video/live/` because it needs real YouTube: `check-live-tests-registered.py` scans that
 * directory and fails the build for anything missing from the live list, and a test needing the real
 * service that CI runs from a datacentre IP is red every time — which is what the guard is for.
 *
 * Dewi, 2026-08-31: *"make sure this works also by sharing to the app multiple urls in quick
 * succession"*. It is the sharpest version of the case that broke parallel downloading on its first
 * run: every share is a separate intent, each resolves for seconds, each mutates the queue, and the
 * automatic downloader re-plans on every mutation. So three shares a second apart produce
 * overlapping resolves, overlapping passes and overlapping claims on the same items — which is
 * precisely how three queued videos came to start six downloads.
 *
 * Two things are asserted, and they fail differently:
 *
 * - **every shared link is in the queue.** One share barging another out, or a resolve losing a
 *   race, shows up here and nowhere else.
 * - **nothing was started twice.** Counted from the app's own `[download] start` trail rather than
 *   from the final state, because two downloads of one item leave exactly one row behind — the
 *   damage (a file written by two coroutines, and twice the data) is invisible afterwards.
 */
class SeveralSharedLinksAllLandTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container

    private val startsPerItem = mutableMapOf<String, Int>()
    private var previousSink: Diag.Sink? = null

    @Before
    fun startFromNothingSharedOrDownloaded() = runBlocking {
        container.playbackQueue.clear()
        SHARED.forEach {
            container.downloadManager.cancel(MediaItemId(it))
            container.downloadManager.delete(MediaItemId(it))
        }
        container.appPreferences.setAutoDownloadQueue(true)
        container.appPreferences.setAutoDownloadWifiOnly(false)
        container.startQueueAutoDownload()
        previousSink = Diag.sink
        Diag.sink = Diag.Sink { level, tag, message, error ->
            previousSink?.write(level, tag, message, error)
            if (tag == "download" && message.startsWith("start ")) {
                synchronized(startsPerItem) {
                    startsPerItem[message] = (startsPerItem[message] ?: 0) + 1
                }
            }
        }
    }

    @After
    fun leaveNothingBehind() = runBlocking {
        previousSink?.let { Diag.sink = it }
        SHARED.forEach {
            container.downloadManager.cancel(MediaItemId(it))
            container.downloadManager.delete(MediaItemId(it))
        }
        container.playbackQueue.clear()
    }

    @Test
    fun everySharedLinkIsQueuedAndFetchedExactlyOnce() = runBlocking {
        SHARED.forEach { videoId ->
            context.startActivity(shareOf(videoId))
            // A realistic gap: fast enough that the resolves overlap, slow enough to be three
            // separate taps on a share sheet rather than a synthetic burst.
            Thread.sleep(SHARE_GAP_MS)
        }

        val queued = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            container.playbackQueue.state.first { snapshot ->
                snapshot.entries.map { it.item.item.id.value }.containsAll(SHARED)
            }
        }
        Log.i("dewidebug", "queued after sharing: ${queued?.entries?.map { it.item.item.id.value }}")
        assertTrue(
            "not every shared link reached the queue — one share barged another out, or a resolve " +
                "lost a race. Got: ${queued?.entries?.map { it.item.item.id.value } ?: "nothing in time"}",
            queued != null,
        )

        val fetched = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            container.downloadManager.observeDownloads().first { states ->
                SHARED.all { states[MediaItemId(it)] is DownloadState.Downloaded }
            }
        }
        assertTrue("not everything shared was fetched for offline listening", fetched != null)

        val repeats = synchronized(startsPerItem) { startsPerItem.filterValues { it > 1 } }
        assertEquals(
            "an item was started more than once, so two coroutines wrote the same file for twice " +
                "the data: $repeats",
            emptyMap<String, Int>(),
            repeats,
        )
    }

    private fun shareOf(videoId: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$videoId")
        setPackage(context.packageName)
        // NEW_TASK because this is sent from the application context, and SINGLE_TOP so the second
        // and third arrive at the running activity as onNewIntent — which is how a share sheet
        // delivers them, and the path where one can be lost.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private companion object {
        /** Three known-good videos, shared as three separate links. */
        val SHARED = listOf("jNQXAC9IVRw", "aqz-KE-bpKQ", "ttiLcMUQq80")

        const val SHARE_GAP_MS = 700L
        const val RESOLVE_TIMEOUT_MS = 180_000L
        const val DOWNLOAD_TIMEOUT_MS = 300_000L
    }
}
