package com.dewijones92.totum.data.download

import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TransientRetriesTest {

    private val id = MediaItemId("torrent:abc:1")
    private val retried = mutableListOf<MediaItemId>()

    private fun TestScope.retries(mayRetry: () -> Boolean = { true }) = TransientRetries(
        backgroundScope,
        retry = { retried += it },
        nowMs = { testScheduler.currentTime },
        mayRetryNow = mayRetry,
    )

    @Test
    fun `each retry waits four times longer than the last, then it stops`() = runTest {
        val retries = retries()
        val waits = mutableListOf<Long>()
        repeat(5) {
            retries.failed(id, "film", DownloadState.Failed("timeout"))
            waits += (retries.planned.value[id]?.atMs ?: -1) - testScheduler.currentTime
            advanceTimeBy(40 * 60_000L)
        }

        assertEquals(listOf(30_000L, 120_000L, 480_000L, 1_920_000L, -1L - 0), waits.map { if (it < 0) -1L else it })
        assertEquals(4, retried.size)
    }

    @Test
    fun `a success starts the count again, and a cancel stops a waiting retry`() = runTest {
        val retries = retries()
        retries.failed(id, "film", DownloadState.Failed("timeout"))
        retries.succeeded(id)
        retries.failed(id, "film", DownloadState.Failed("timeout"))
        assertEquals(1, retries.planned.value[id]?.attempt)

        retries.forget(id)
        advanceTimeBy(60_000)
        assertEquals(emptyList<MediaItemId>(), retried)
    }

    @Test
    fun `a retry waits for a usable network`() = runTest {
        var online = false
        val retries = retries { online }
        retries.failed(id, "film", DownloadState.Failed("Unable to connect"))
        advanceTimeBy(5 * 60_000L)
        assertEquals(emptyList<MediaItemId>(), retried)

        online = true
        advanceTimeBy(61_000)
        assertEquals(listOf(id), retried)
    }
}
