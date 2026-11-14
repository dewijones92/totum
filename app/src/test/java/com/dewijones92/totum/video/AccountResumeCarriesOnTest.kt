package com.dewijones92.totum.video

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.fake.InMemoryReconciledAccountProgress
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountResumeCarriesOnTest {

    private val history = FakeYouTubeWatchHistory()
    private val localPositions = mutableMapOf<String, Long>()
    private val finishedHere = mutableSetOf<String>()
    private val reconciled = InMemoryReconciledAccountProgress()
    private val seen = MutableStateFlow<Map<String, AccountProgress>>(emptyMap())
    private var clock = 0L
    private var offline = false
    private val id = MediaItemId("abc")
    private val duration = 6_253_000L

    private fun TestScope.positions() = AccountResumePositions(
        local = { item ->
            when {
                item.value in finishedHere -> PlayState.Played
                else -> localPositions[item.value]?.let { PlayState.InProgress(it, duration) } ?: PlayState.Unplayed
            }
        },
        adopt = { item, position, _ -> localPositions[item.value] = position },
        history = history,
        scope = backgroundScope,
        reconciled = reconciled,
        offline = { offline },
        now = { clock },
        seen = seen,
    )

    private fun account(positionMs: Long) =
        mapOf("abc" to AccountProgress(positionMs = positionMs, durationMs = duration))

    @Test
    fun `a paused item whose account position is well ahead carries on there, adopts it and records it`() =
        runTest {
            history.watched = account(1_501_000)
            localPositions["abc"] = 1_040_000

            val carried = positions().watchedFurtherElsewhereMs(id, playingAtMs = 1_040_000, pausedMs = 30_000)

            assertEquals(1_501_000L, carried)
            assertEquals(1_501_000L, localPositions["abc"])
            assertEquals(1_501_000L, reconciled.reconciledMs(id))
        }

    @Test
    fun `the same account figure is never carried on to twice`() = runTest {
        history.watched = account(1_501_000)
        val p = positions()
        assertEquals(1_501_000L, p.watchedFurtherElsewhereMs(id, 1_040_000, 30_000))

        assertNull(p.watchedFurtherElsewhereMs(id, playingAtMs = 200_000, pausedMs = 30_000))
        assertNull(p.watchedFurtherElsewhereMs(id, playingAtMs = 1_040_000, pausedMs = 30_000))
    }

    @Test
    fun `a figure that has moved since the last carry-on carries on again`() = runTest {
        history.watched = account(1_501_000)
        val p = positions()
        assertEquals(1_501_000L, p.watchedFurtherElsewhereMs(id, 1_040_000, 30_000))

        history.watched = account(3_000_000)
        clock += 6 * 60_000L

        assertEquals(3_000_000L, p.watchedFurtherElsewhereMs(id, playingAtMs = 1_501_000, pausedMs = 30_000))
    }

    @Test
    fun `an account position behind the player stays put and the player's own is not overwritten`() = runTest {
        history.watched = account(2_400_000)
        localPositions["abc"] = 3_000_000

        assertNull(positions().watchedFurtherElsewhereMs(id, playingAtMs = 3_000_000, pausedMs = 30_000))

        assertEquals(3_000_000L, localPositions["abc"])
    }

    @Test
    fun `an account position within one percent of the player is noise and stays put`() = runTest {
        history.watched = account(2_450_000)

        assertNull(positions().watchedFurtherElsewhereMs(id, playingAtMs = 2_400_000, pausedMs = 30_000))
    }

    @Test
    fun `just over one percent ahead carries on`() = runTest {
        history.watched = account(2_400_000 + 62_531)

        assertEquals(2_462_531L, positions().watchedFurtherElsewhereMs(id, playingAtMs = 2_400_000, pausedMs = 30_000))
    }

    @Test
    fun `an item finished on this phone is never carried on, whatever the account says`() = runTest {
        history.watched = account(3_000_000)
        finishedHere += "abc"

        assertNull(positions().watchedFurtherElsewhereMs(id, playingAtMs = 0, pausedMs = 30_000))

        assertNull(reconciled.reconciledMs(id))
        assertNull(localPositions["abc"])
    }

    @Test
    fun `youtube's ten percent floor says only started and is neither carried on nor recorded`() = runTest {
        history.watched = account(625_000)

        assertNull(positions().watchedFurtherElsewhereMs(id, playingAtMs = 0, pausedMs = 30_000))

        assertNull(reconciled.reconciledMs(id))
        assertNull(localPositions["abc"])
    }

    @Test
    fun `an account that says finished does not throw the paused item to the end`() = runTest {
        history.watched = account(duration)
        localPositions["abc"] = 1_000_000

        assertNull(positions().watchedFurtherElsewhereMs(id, playingAtMs = 1_000_000, pausedMs = 30_000))

        assertEquals(1_000_000L, localPositions["abc"])
    }

    @Test
    fun `an item the account has never heard of has nothing to carry on to`() = runTest {
        assertNull(positions().watchedFurtherElsewhereMs(id, playingAtMs = 1_000_000, pausedMs = 30_000))
        assertNull(reconciled.reconciledMs(id))
    }

    @Test
    fun `a reopen with no pause length is judged the same way`() = runTest {
        history.watched = account(1_501_000)

        assertEquals(1_501_000L, positions().watchedFurtherElsewhereMs(id, playingAtMs = 1_040_000, pausedMs = null))
    }

    @Test
    fun `offline, a figure already held still carries on and the account is not asked`() = runTest {
        offline = true
        seen.value = account(1_501_000)

        assertEquals(1_501_000L, positions().watchedFurtherElsewhereMs(id, playingAtMs = 1_040_000, pausedMs = 30_000))
    }

    @Test
    fun `offline with nothing held there is nothing to carry on to`() = runTest {
        offline = true

        assertNull(positions().watchedFurtherElsewhereMs(id, playingAtMs = 1_040_000, pausedMs = 30_000))
    }

    @Test
    fun `an account holding twelve hundred positions answers each item from its own figure`() = runTest {
        history.watched = (1..PROGRESS_RECORDS).associate {
            "v$it" to AccountProgress(positionMs = 1_000_000L + it * 1_000L, durationMs = duration)
        }
        val p = positions()

        val carried = (1..PROGRESS_RECORDS).map { p.watchedFurtherElsewhereMs(MediaItemId("v$it"), 100_000, 30_000) }

        assertEquals((1..PROGRESS_RECORDS).map { 1_000_000L + it * 1_000L }, carried)
        assertEquals(PROGRESS_RECORDS, localPositions.size)
    }

    private companion object {
        const val PROGRESS_RECORDS = 1_207
    }
}
