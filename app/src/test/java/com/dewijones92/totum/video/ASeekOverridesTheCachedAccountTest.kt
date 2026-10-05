package com.dewijones92.totum.video

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.fake.InMemoryReconciledAccountProgress
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.Chosen
import com.dewijones92.totum.playback.fake.InMemoryPlaybackProgressStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ASeekOverridesTheCachedAccountTest {
    @Test
    fun `rewind before the first account resume keeps the chosen zero`() = runTest {
        val id = MediaItemId("LwQQ7nBCGSs")
        val local = InMemoryPlaybackProgressStore()
        val reconciled = InMemoryReconciledAccountProgress()
        val history = FakeYouTubeWatchHistory().apply {
            watched = mapOf(id.value to AccountProgress(3_038_880, 5_844_000))
        }
        fun positions() = AccountResumePositions(
            local = local::playState,
            history = history,
            scope = backgroundScope,
            reconciled = reconciled,
        )
        val account = positions()
        val store = AccountPlaybackProgressStore(local, account)
        account.refresh()
        local.save(id, 3_097_370, 5_844_000)
        store.save(id, 0, 5_844_000, Chosen.BY_SEEKING)

        assertEquals(0L, store.resumePositionMs(id))
        assertEquals(0L, positions().resumePositionMs(id))

        history.watched = mapOf(id.value to AccountProgress(4_000_000, 5_844_000))
        assertEquals(4_000_000L, positions().resumePositionMs(id))
    }

    @Test
    fun `ordinary playback does not discard progress made elsewhere`() = runTest {
        val id = MediaItemId("video")
        val local = InMemoryPlaybackProgressStore()
        val history = FakeYouTubeWatchHistory().apply {
            watched = mapOf(id.value to AccountProgress(3_038_880, 5_844_000))
        }
        val account = AccountResumePositions(local = local::playState, history = history, scope = backgroundScope)
        account.refresh()
        val store = AccountPlaybackProgressStore(local, account)
        store.save(id, 10_000, 5_844_000)

        assertEquals(3_038_880L, store.resumePositionMs(id))
    }
}
