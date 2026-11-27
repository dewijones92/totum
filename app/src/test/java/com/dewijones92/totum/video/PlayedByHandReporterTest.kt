package com.dewijones92.totum.video

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.fake.InMemoryAccountProgressOutbox
import com.dewijones92.totum.domain.fake.InMemoryReconciledAccountProgress
import com.dewijones92.totum.innertube.history.SessionResult
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.Chosen
import com.dewijones92.totum.playback.PlaybackProgressStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayedByHandReporterTest {

    private val history = FakeYouTubeWatchHistory()
    private val outbox = InMemoryAccountProgressOutbox()
    private val rows = MutableStateFlow<Map<MediaItemId, PlayState>>(emptyMap())
    private val lengths = mutableMapOf<MediaItemId, Long>()
    private val accountHolds = mutableSetOf<MediaItemId>()
    private var held = false
    private lateinit var drain: ProgressOutboxDrain

    private val video = MediaItemId("videoAAAAAA")

    @Before
    fun clearTheTrail(): Unit = Breadcrumbs.clear()

    private class RawStore(private val rows: MutableStateFlow<Map<MediaItemId, PlayState>>) : PlaybackProgressStore {
        override suspend fun resumePositionMs(itemId: MediaItemId): Long? = null
        override suspend fun playState(itemId: MediaItemId): PlayState = rows.value[itemId] ?: PlayState.Unplayed
        override suspend fun save(itemId: MediaItemId, positionMs: Long, durationMs: Long?, chosen: Chosen) = Unit
        override fun observeStates(): Flow<Map<MediaItemId, PlayState>> = rows.map { it }
        override suspend fun setPlayed(itemId: MediaItemId, played: Boolean) {
            rows.value = if (played) rows.value + (itemId to PlayState.Played) else rows.value - itemId
        }
    }

    private fun TestScope.store(): AccountPlaybackProgressStore {
        val resume = AccountResumePositions(
            local = { rows.value[it] ?: PlayState.Unplayed },
            history = history,
            scope = backgroundScope,
            reconciled = InMemoryReconciledAccountProgress(),
        )
        drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        val byHand = PlayedByHandReporter(
            outbox = outbox,
            drain = drain,
            durationOf = { lengths[it] },
            accountHolds = { it in accountHolds },
            held = { held },
            now = { 5L },
        )
        return AccountPlaybackProgressStore(RawStore(rows), resume, byHand)
    }

    @Test
    fun `marking a YouTube item played by hand tells the account it finished`() = runTest {
        lengths[video] = 600_000
        val store = store()

        store.setPlayed(video, true)
        runCurrent()

        val report = history.reports.single()
        assertEquals(video.value, report.videoId)
        assertTrue(report.finished)
        assertEquals(600f, report.lengthSec)
        assertEquals(PlayState.Played, rows.value[video])
    }

    @Test
    fun `marking an item that is already Played changes nothing on the account`() = runTest {
        lengths[video] = 600_000
        rows.value = mapOf(video to PlayState.Played)
        val store = store()

        store.setPlayed(video, true)
        runCurrent()

        assertTrue(history.reports.toString(), history.reports.isEmpty())
    }

    @Test
    fun `a podcast marked played is no business of YouTube's`() = runTest {
        val episode = MediaItemId("https://example.org/ep/1")
        lengths[episode] = 600_000
        val store = store()

        store.setPlayed(episode, true)
        runCurrent()

        assertTrue(history.reports.isEmpty())
        assertEquals(emptyList<MediaItemId>(), outbox.pending().map { it.itemId })
    }

    @Test
    fun `an item with no length known is marked locally and says why the account was not told`() = runTest {
        val store = store()

        store.setPlayed(video, true)
        runCurrent()

        assertEquals(PlayState.Played, rows.value[video])
        assertTrue(history.reports.isEmpty())
        assertTrue(
            Breadcrumbs.snapshot().any { it.tag == "yt-sync-item" && it.message.contains("no length known") },
        )
    }

    @Test
    fun `a reset pings 0 for an item the account holds a position for`() = runTest {
        lengths[video] = 600_000
        accountHolds += video
        rows.value = mapOf(video to PlayState.InProgress(300_000, 600_000))
        val store = store()

        store.setPlayed(video, false)
        runCurrent()

        val report = history.reports.single()
        assertEquals(0f, report.positionSec)
        assertEquals(false, report.finished)
        assertEquals(null, rows.value[video])
    }

    @Test
    fun `a reset of an item the account has never seen does not create a history entry`() = runTest {
        lengths[video] = 600_000
        rows.value = mapOf(video to PlayState.InProgress(300_000, 600_000))
        val store = store()

        store.setPlayed(video, false)
        runCurrent()

        assertTrue(history.reports.isEmpty())
        assertEquals(null, rows.value[video])
    }

    @Test
    fun `under a test run's hold the mark is local only`() = runTest {
        lengths[video] = 600_000
        held = true
        val store = store()

        store.setPlayed(video, true)
        runCurrent()

        assertTrue(history.reports.isEmpty())
        assertEquals(PlayState.Played, rows.value[video])
    }

    @Test
    fun `a video YouTube refuses for good is not queued again`() = runTest {
        lengths[video] = 600_000
        history.sessionResults[video.value] = SessionResult.Refused("UNPLAYABLE: claimed content")
        val store = store()
        store.setPlayed(video, true)
        repeat(ProgressOutboxDrain.GIVE_UP_REFUSED_AFTER) { drain.drain() }
        val attempts = history.sessionAttempts.size
        store.setPlayed(video, false)
        accountHolds += video

        store.setPlayed(video, true)
        runCurrent()

        assertEquals(emptyList<MediaItemId>(), outbox.pending().map { it.itemId })
        assertEquals(attempts, history.sessionAttempts.size)
    }
}
