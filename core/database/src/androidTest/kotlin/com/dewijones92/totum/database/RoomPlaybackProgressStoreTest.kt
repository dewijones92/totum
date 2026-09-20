package com.dewijones92.totum.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.playback.Chosen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RoomPlaybackProgressStoreTest {

    private lateinit var database: TotumDatabase
    private lateinit var store: RoomPlaybackProgressStore
    private val id = MediaItemId("vid-1")

    @Before
    fun createStore() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TotumDatabase::class.java,
        ).build()
        store = RoomPlaybackProgressStore(database.playbackProgressDao()) { 0L }
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun savesAndResumesAPosition() = runTest {
        store.save(id, positionMs = 42_000, durationMs = 600_000)
        assertEquals(42_000L, store.resumePositionMs(id))
    }

    @Test
    fun ignoresTrivialPositionsSoAQuickTapLeavesNoResumePoint() = runTest {
        store.save(id, positionMs = 1_000, durationMs = 600_000)
        assertNull(store.resumePositionMs(id))
    }

    @Test
    fun nearTheEndCountsAsFinishedAndClearsAnyResumePoint() = runTest {
        store.save(id, positionMs = 100_000, durationMs = 600_000)
        assertEquals(100_000L, store.resumePositionMs(id))

        // Watched to within the last few seconds: it should restart next time.
        store.save(id, positionMs = 599_000, durationMs = 600_000)
        assertNull(store.resumePositionMs(id))
    }

    /**
     * THE bug in report 0.1.496: *"I have tried to rewind the video back to the start but it is not
     * working"*. The floor that stops a quick tap creating a resume point was also throwing away
     * every save that would have moved an existing one back — Dewi paused at 3790ms, 2595ms and
     * 1144ms after rewinding and all three were discarded, so the store still answered 11273.
     */
    @Test
    fun aRewindBelowTheFloorStillMovesAnExistingPosition() = runTest {
        store.save(id, positionMs = 11_273, durationMs = 777_000)

        store.save(id, positionMs = 1_144, durationMs = 777_000)

        assertEquals(1_144L, store.resumePositionMs(id))
    }

    /** Forwards under the floor is not a rewind, so the floor still applies and nothing is created. */
    @Test
    fun aTrivialPositionStillCreatesNothingWhereThereIsNoResumePoint() = runTest {
        store.save(id, positionMs = 1_144, durationMs = 777_000)

        assertNull(store.resumePositionMs(id))
    }

    /** What the floor was really protecting: replaying a played item for three seconds is not unplaying it. */
    @Test
    fun aShortReplayDoesNotUnPlayAPlayedItem() = runTest {
        store.setPlayed(id, played = true)

        store.save(id, positionMs = 3_000, durationMs = 777_000)

        assertEquals(PlayState.Played, store.observeStates().first()[id])
    }

    /**
     * And nor does a REINSTATED one. Adopting an account figure that is merely behind must not
     * un-play something finished here — and the account is behind on everything it has not heard
     * about since the outbound half stopped working, which was most of the library.
     */
    @Test
    fun aReinstatedPositionDoesNotUnPlayAPlayedItem() = runTest {
        store.setPlayed(id, played = true)

        store.save(id, positionMs = 3_000, durationMs = 777_000, chosen = Chosen.AS_A_RECORD)

        assertEquals(PlayState.Played, store.observeStates().first()[id])
    }

    /**
     * A SEEK is different, and has to be: the person is watching it again from there. Keeping the
     * row completed would store a position `resumePositionMs` refuses to return — scrub to forty
     * minutes of a podcast you have finished, come back, and it silently starts from zero.
     */
    @Test
    fun aSeekOnAPlayedItemReopensItAtThatPosition() = runTest {
        store.setPlayed(id, played = true)

        store.save(id, positionMs = 400_000, durationMs = 777_000, chosen = Chosen.BY_SEEKING)

        assertEquals(400_000L, store.resumePositionMs(id))
        assertEquals(PlayState.InProgress(400_000, 777_000), store.observeStates().first()[id])
    }

    /** And scrubbing to the very end finishes it, rather than leaving a zero-length resume. */
    @Test
    fun aSeekToTheEndStillMarksItPlayed() = runTest {
        store.save(id, positionMs = 100_000, durationMs = 777_000)

        store.save(id, positionMs = 777_000, durationMs = 777_000, chosen = Chosen.BY_SEEKING)

        assertEquals(PlayState.Played, store.observeStates().first()[id])
    }

    /**
     * Nor does a reinstated one CREATE completion. YouTube reports whole percents, so a 99% tile
     * is 769,230ms of a 777,000ms video — past the near-the-end tail. Adopting that marked the
     * item played before a frame had run here, and the next tap then started it from the beginning.
     */
    @Test
    fun aReinstatedPositionNearTheEndDoesNotMarkItPlayed() = runTest {
        store.save(id, positionMs = 769_230, durationMs = 777_000, chosen = Chosen.AS_A_RECORD)

        assertEquals(769_230L, store.resumePositionMs(id))
        assertEquals(PlayState.InProgress(769_230, 777_000), store.observeStates().first()[id])
    }

    /** Reaching the end is still what marks it played — the ground truth, not a guess at one. */
    @Test
    fun reachingTheEndStillMarksItPlayed() = runTest {
        store.save(id, positionMs = 769_230, durationMs = 777_000)

        assertEquals(PlayState.Played, store.observeStates().first()[id])
    }

    /**
     * `playState` exists because `resumePositionMs` collapses FINISHED and NEVER-PLAYED into the
     * same null, which is why a video finished on this phone still took YouTube's stale figure.
     * Every other test here goes through `resumePositionMs` and so cannot tell those apart —
     * dropping the completion check from `playState` would leave the whole suite green and quietly
     * reinstate the defect.
     */
    @Test
    fun playStateTellsFinishedFromNeverPlayed() = runTest {
        assertEquals(PlayState.Unplayed, store.playState(MediaItemId("never-touched")))

        store.save(id, positionMs = 42_000, durationMs = 600_000)
        assertEquals(PlayState.InProgress(42_000, 600_000), store.playState(id))

        store.setPlayed(id, played = true)
        assertEquals(PlayState.Played, store.playState(id))
        assertNull("and a finished item still reports no position to resume", store.resumePositionMs(id))
    }

    @Test
    fun unknownItemResumesFromTheStart() = runTest {
        assertNull(store.resumePositionMs(MediaItemId("never-played")))
    }

    /** The whole point of keeping the row: played and never-started must differ. */
    @Test
    fun finishingMarksPlayedRatherThanForgetting() = runTest {
        store.save(id, positionMs = 599_000, durationMs = 600_000)

        assertEquals(PlayState.Played, store.observeStates().first()[id])
        assertNull(store.observeStates().first()[MediaItemId("never-played")])
    }

    @Test
    fun apartWayItemReportsItsProgress() = runTest {
        store.save(id, positionMs = 150_000, durationMs = 600_000)

        assertEquals(PlayState.InProgress(150_000, 600_000), store.observeStates().first()[id])
        assertEquals(0.25f, (store.observeStates().first()[id] as PlayState.InProgress).fraction)
    }

    @Test
    fun markingPlayedByHandNeedsNoPriorPlayback() = runTest {
        store.setPlayed(id, played = true)

        assertEquals(PlayState.Played, store.observeStates().first()[id])
        assertNull(store.resumePositionMs(id))
    }

    /**
     * A flat 15s tail is right for a normal item and absurd for a Short: it marked a
     * 30-second video played at the halfway point, and a 60-second one at 75%.
     */
    @Test
    fun aShortIsNotCalledPlayedHalfwayThrough() = runTest {
        val short = MediaItemId("short-30s")
        store.save(short, positionMs = 16_000, durationMs = 30_000)
        assertEquals(PlayState.InProgress(16_000, 30_000), store.observeStates().first()[short])

        store.save(short, positionMs = 27_500, durationMs = 30_000)
        assertEquals(PlayState.Played, store.observeStates().first()[short])
    }

    /** The tail stays 15s for anything long enough that 10% would be minutes. */
    @Test
    fun aLongItemKeepsTheFifteenSecondTail() = runTest {
        val podcast = MediaItemId("podcast-3h")
        val threeHours = 3 * 60 * 60 * 1_000L
        store.save(podcast, positionMs = threeHours - 20_000, durationMs = threeHours)
        assertEquals(
            PlayState.InProgress(threeHours - 20_000, threeHours),
            store.observeStates().first()[podcast],
        )

        store.save(podcast, positionMs = threeHours - 10_000, durationMs = threeHours)
        assertEquals(PlayState.Played, store.observeStates().first()[podcast])
    }

    @Test
    fun markingUnplayedClearsTheStateEntirely() = runTest {
        store.save(id, positionMs = 150_000, durationMs = 600_000)

        store.setPlayed(id, played = false)

        assertNull(store.observeStates().first()[id])
        assertNull(store.resumePositionMs(id))
    }

    /**
     * Replaying a finished item ticks through small positions first. Those must not
     * clear the played mark, or every replay would silently mark the item unplayed.
     */
    @Test
    fun replayingAPlayedItemKeepsItPlayedUntilRealProgress() = runTest {
        store.setPlayed(id, played = true)

        store.save(id, positionMs = 1_000, durationMs = 600_000)

        assertEquals(PlayState.Played, store.observeStates().first()[id])
    }
}
