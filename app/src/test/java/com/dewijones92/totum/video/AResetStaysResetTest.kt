package com.dewijones92.totum.video

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.accountAwarePlayState
import com.dewijones92.totum.domain.fake.InMemoryReconciledAccountProgress
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.InMemoryPlaybackProgressStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AResetStaysResetTest {
    private val id = MediaItemId("resetme")
    private val hour = 3_600_000L
    private val local = InMemoryPlaybackProgressStore()
    private val reconciled = InMemoryReconciledAccountProgress()
    private val history = FakeYouTubeWatchHistory()

    private fun positions(scope: CoroutineScope) =
        AccountResumePositions(local = local::playState, history = history, scope = scope, reconciled = reconciled)

    private suspend fun rowState(account: AccountResumePositions): PlayState {
        val remote = account.watched.first()[id.value]
        return accountAwarePlayState(
            local.playState(id).takeUnless { it is PlayState.Unplayed },
            remote?.positionMs,
            remote?.durationMs,
            reconciled.reconciledMs(id),
        )
    }

    @Test
    fun `marking an item unplayed keeps the account's old position from winning it back on the next play`() = runTest {
        history.watched = mapOf(id.value to AccountProgress(hour / 2, hour))
        val account = positions(backgroundScope)
        account.refresh()
        val store = AccountPlaybackProgressStore(local, account)
        local.save(id, 10_000, hour)

        store.setPlayed(id, false)

        assertNull(store.resumePositionMs(id))
        assertEquals(hour / 2, reconciled.reconciledMs(id))
    }

    @Test
    fun `marking an item unplayed keeps its row Unplayed instead of showing the account's progress`() = runTest {
        history.watched = mapOf(id.value to AccountProgress(hour / 2, hour))
        val account = positions(backgroundScope)
        account.refresh()
        val store = AccountPlaybackProgressStore(local, account)
        local.save(id, 10_000, hour)

        store.setPlayed(id, false)

        assertEquals(PlayState.Unplayed, rowState(account))
    }

    @Test
    fun `marking an item unplayed after the account said finished does not leave it Played`() = runTest {
        history.watched = mapOf(id.value to AccountProgress(hour, hour))
        val account = positions(backgroundScope)
        account.refresh()
        val store = AccountPlaybackProgressStore(local, account)

        store.setPlayed(id, false)

        assertEquals(PlayState.Unplayed, rowState(account))
        assertNull(store.resumePositionMs(id))
    }

    @Test
    fun `watching further elsewhere after the reset still wins, which is news and not an echo`() = runTest {
        history.watched = mapOf(id.value to AccountProgress(hour / 2, hour))
        val account = positions(backgroundScope)
        account.refresh()
        val store = AccountPlaybackProgressStore(local, account)
        store.setPlayed(id, false)

        history.watched = mapOf(id.value to AccountProgress(hour * 3 / 4, hour))

        assertEquals(hour * 3 / 4, positions(backgroundScope).resumePositionMs(id))
    }

    @Test
    fun `marking an item played does not touch the account figure`() = runTest {
        history.watched = mapOf(id.value to AccountProgress(hour / 2, hour))
        val account = positions(backgroundScope)
        account.refresh()
        val store = AccountPlaybackProgressStore(local, account)

        store.setPlayed(id, true)

        assertNull(reconciled.reconciledMs(id))
        assertEquals(PlayState.Played, local.playState(id))
    }

    @Test
    fun `an item the account knows nothing about is simply reset`() = runTest {
        val store = AccountPlaybackProgressStore(local, positions(backgroundScope))
        local.save(id, 10_000, hour)

        store.setPlayed(id, false)

        assertNull(reconciled.reconciledMs(id))
        assertEquals(PlayState.Unplayed, local.playState(id))
    }

    @Test
    fun `resetting a whole group of items records each one but logs only a few lines`() = runTest {
        val ids = (1..1_290).map { MediaItemId("bulk$it") }
        history.watched = ids.associate { it.value to AccountProgress(hour / 2, hour) }
        val account = positions(backgroundScope)
        account.refresh()
        val store = AccountPlaybackProgressStore(local, account)
        Breadcrumbs.clear()

        ids.forEach { store.setPlayed(it, false) }

        assertEquals(1_290, ids.count { reconciled.reconciledMs(it) == hour / 2 })
        val lines = Breadcrumbs.snapshot().count { it.tag == "yt-sync" && "marked unplayed" in it.message }
        assertEquals("5 in full, then one per hundred (12)", 17, lines)
    }
}
