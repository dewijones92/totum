package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomeRetry
import com.dewijones92.totum.data.home.PhoneDecoder
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.operations.Ended
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRetryingJobsTest {

    private val video = PlayableItem(
        MediaItem(MediaItemId("abcdefghijk"), SourceId("s"), "A film", publishedAt = null, duration = null),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=abcdefghijk")),
    )
    private val api = FakeHomeApi()
    private val operations = Operations()
    private val tokens = OAuthTokens(AccessToken("a"), RefreshToken("r"), 9)

    private fun job(state: HomeJobState, retry: HomeRetry? = null, error: String? = null) =
        HomeJob("abcdefghijk", video.toBackup(), state, false, bytes = 5, totalBytes = 10, error = error, retry = retry)

    private fun home(scope: kotlinx.coroutines.CoroutineScope) = HomeDownloads(
        api,
        PhoneSide({ tokens }, { listOf(PhoneDecoder("avc", 1080, true)) }, { false }),
        FakeDownloadManager(),
        operations,
        NoHomeNotices,
        scope,
        Pacing(pollMs = 1),
    )

    @Test
    fun `a failure the Pi is retrying does not end the strip as failed, an exhausted one does`() = runTest {
        val ended = mutableListOf<Ended>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { operations.ended.collect { ended += it } }
        val home = home(this)

        api.listed = listOf(job(HomeJobState.DOWNLOADING))
        home.refresh("running")
        advanceUntilIdle()
        api.listed = listOf(
            job(HomeJobState.FAILED, HomeRetry(2, 4, System.currentTimeMillis() + 60_000), "timeout"),
        )
        home.refresh("failed, retrying")
        advanceUntilIdle()

        assertTrue("the strip is still showing it: $ended", ended.isEmpty())
        assertEquals(1, operations.running.value.size)

        api.listed = listOf(job(HomeJobState.FAILED, error = "timeout"))
        home.refresh("out of retries")
        advanceUntilIdle()

        assertEquals(listOf<OperationEnd>(OperationEnd.Failed("timeout")), ended.map { it.end })
    }

    @Test
    fun `a job waiting for its retry is polled at the busy pace and the strip says when it tries again`() = runTest {
        val home = home(this)
        api.listed = listOf(job(HomeJobState.DOWNLOADING))
        home.refresh("running")
        advanceUntilIdle()
        val at = System.currentTimeMillis() + 60_000
        api.listed = listOf(job(HomeJobState.WAITING, HomeRetry(2, 4, at), "timeout"))
        home.refresh("waiting for the retry")
        advanceUntilIdle()

        val shown = operations.running.value.single()
        assertNotNull(shown.retry)
        assertEquals(2, shown.retry!!.attempt)
        assertEquals(4, shown.retry!!.of)
        assertEquals(at, shown.retry!!.atMs)
        assertEquals("timeout", shown.retry!!.reason)

        api.listed = listOf(job(HomeJobState.DONE))
        home.refresh("done")
        advanceUntilIdle()
        assertTrue(operations.running.value.isEmpty())
    }
}
