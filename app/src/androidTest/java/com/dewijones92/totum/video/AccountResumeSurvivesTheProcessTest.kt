package com.dewijones92.totum.video

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.database.RoomPlaybackProgressStore
import com.dewijones92.totum.database.RoomReconciledAccountProgress
import com.dewijones92.totum.database.TotumDatabase
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.Chosen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The whole answer, through the REAL stores: a rewind survives a frozen account figure, and
 * survives the app being killed.
 *
 * Every other test of this rule uses in-memory doubles on each side, so the rule and its storage
 * were only ever checked apart — and the first cut of this fix passed all of them while still
 * answering 77700 for the `local=none` lines in report 0.1.496. This wires the real
 * `RoomPlaybackProgressStore` to the real `RoomReconciledAccountProgress` and asks the question
 * Dewi asked: *"I have tried to rewind the video back to the start but it is not working"*.
 *
 * The account's own figure is a fake, deliberately: YouTube being frozen at 77700ms is the
 * premise, not the thing under test.
 */
class AccountResumeSurvivesTheProcessTest {

    private lateinit var database: TotumDatabase
    private lateinit var progress: RoomPlaybackProgressStore
    private lateinit var reconciled: RoomReconciledAccountProgress
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = FakeYouTubeWatchHistory()
    private val id = MediaItemId("vceHVwxOnhA")

    @Before
    fun create() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TotumDatabase::class.java,
        ).build()
        progress = RoomPlaybackProgressStore(database.playbackProgressDao())
        reconciled = RoomReconciledAccountProgress(database.reconciledAccountProgressDao())
        // Frozen, exactly as it was in the report: the outbound half was refused with 123 updates
        // held, so this figure could never move however much he watched.
        history.watched = mapOf(id.value to AccountProgress(positionMs = 77_700, durationMs = 777_000))
    }

    @After
    fun close() {
        scope.cancel()
        database.close()
    }

    /** A new instance each time, because the app is killed and reopened and must not forget. */
    private fun positions() = AccountResumePositions(
        local = progress::playState,
        adopt = { item, position, duration -> progress.save(item, position, duration, Chosen.AS_A_RECORD) },
        history = history,
        scope = scope,
        reconciled = reconciled,
    )

    @Test
    fun aRewindOutlivesTheProcessThatMadeIt() = runBlocking {
        // He has watched a bit here before; the account is further on and rightly wins once.
        progress.save(id, positionMs = 11_273, durationMs = 777_000)
        assertEquals(77_700L, positions().resumePositionMs(id))

        // The rewind, as the seek listener now records it: chosen, so the floor cannot drop it.
        progress.save(id, positionMs = 0, durationMs = 777_000, chosen = Chosen.BY_SEEKING)

        assertEquals("the rewind must stick", 0L, positions().resumePositionMs(id))
        assertEquals("and still stick after a restart", 0L, positions().resumePositionMs(id))
    }

    /** With nothing held here at all — the `local=none` half of the report. */
    @Test
    fun aRewindSticksForAnItemThisDeviceHadNeverPlayed() = runBlocking {
        assertEquals(77_700L, positions().resumePositionMs(id))
        assertEquals("the account's figure is adopted as ours", 77_700L, progress.resumePositionMs(id))

        progress.save(id, positionMs = 0, durationMs = 777_000, chosen = Chosen.BY_SEEKING)

        assertEquals(0L, positions().resumePositionMs(id))
    }

    /** Watched elsewhere for real: the figure moves, and moving is what earns the override. */
    @Test
    fun progressMadeElsewhereStillWins() = runBlocking {
        progress.save(id, positionMs = 11_273, durationMs = 777_000)
        positions().resumePositionMs(id)
        progress.save(id, positionMs = 0, durationMs = 777_000, chosen = Chosen.BY_SEEKING)

        history.watched = mapOf(id.value to AccountProgress(positionMs = 400_000, durationMs = 777_000))

        assertEquals(400_000L, positions().resumePositionMs(id))
    }
}
