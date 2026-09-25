package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Sutton case and its neighbours: what a row shows once YouTube's watched position counts.
 *
 * The judgement about a coarse remote number versus an exact local one lives in [resumeFrom] and is
 * tested there; these pin the mapping onto row states, and the two places it must NOT defer to
 * the remote.
 */
class AccountAwarePlayStateTest {

    private val hour = 3_600_000L

    /** THE case: watched to half on the website, never opened here — the row must show half. */
    @Test
    fun `a video watched elsewhere shows its progress on a row here`() {
        val state = accountAwarePlayState(local = null, remotePositionMs = hour / 2, remoteDurationMs = hour)
        assertEquals(PlayState.InProgress(hour / 2, hour), state)
    }

    /** Report 0.1.514: a video played for six seconds drew a 10% bar, because 10% is YouTube's floor. */
    @Test
    fun `YouTube's ten percent floor draws no progress`() {
        assertEquals(PlayState.Unplayed, accountAwarePlayState(null, hour / 10, hour))
    }

    @Test
    fun `nor does a floor this device adopted before the fix`() {
        val adopted = PlayState.InProgress(hour / 10, hour)

        assertEquals(PlayState.Unplayed, accountAwarePlayState(adopted, hour / 10, hour, hour / 10))
    }

    @Test
    fun `nothing known anywhere is unplayed`() {
        assertEquals(PlayState.Unplayed, accountAwarePlayState(null, null, null))
    }

    @Test
    fun `a device with no remote opinion keeps its own state`() {
        val local = PlayState.InProgress(120_000, hour)
        assertEquals(local, accountAwarePlayState(local, null, null))
    }

    /** Our own pings put the remote number there, rounded DOWN — it is always slightly behind locally. */
    @Test
    fun `a remote position behind the local one does not drag the row back`() {
        val local = PlayState.InProgress(1_699_621, 6_253_000)
        assertEquals(local, accountAwarePlayState(local, remotePositionMs = 1_688_310, remoteDurationMs = 6_253_000))
    }

    @Test
    fun `a remote position meaningfully ahead moves the row on`() {
        val local = PlayState.InProgress(120_000, hour)
        val state = accountAwarePlayState(local, remotePositionMs = hour / 2, remoteDurationMs = hour)
        assertEquals(PlayState.InProgress(hour / 2, hour), state)
    }

    /** Marked played by hand or watched to the end here: exact and deliberate, a percent cannot undo it. */
    @Test
    fun `a local played is final`() {
        assertEquals(
            PlayState.Played,
            accountAwarePlayState(PlayState.Played, remotePositionMs = hour / 3, remoteDurationMs = hour)
        )
    }

    /** YouTube says 100%: it was finished, wherever that happened. */
    @Test
    fun `a remote position at the end is played`() {
        assertEquals(PlayState.Played, accountAwarePlayState(null, remotePositionMs = hour, remoteDurationMs = hour))
    }

    /** A local partial plus a remote finish: the finish wins — you watched it to the end somewhere. */
    @Test
    fun `a remote finish outranks a local part-way`() {
        val local = PlayState.InProgress(120_000, hour)
        assertEquals(PlayState.Played, accountAwarePlayState(local, remotePositionMs = hour, remoteDurationMs = hour))
    }

    /**
     * The bar has to agree with the tap, and after report 0.1.496 the tap ignores a remote figure
     * already acted on. A bar still drawing 40% for an item that now resumes at the start is the
     * drift `accountAwarePlayState` exists to prevent.
     */
    @Test
    fun `a remote figure already acted on does not drag the bar forward`() {
        val local = PlayState.InProgress(0, hour)

        val state = accountAwarePlayState(
            local,
            remotePositionMs = hour / 2,
            remoteDurationMs = hour,
            remoteAlreadyUsedMs = hour / 2,
        )

        assertEquals(PlayState.InProgress(0, hour), state)
    }

    /** And one that has genuinely moved still does, exactly as the tap would. */
    @Test
    fun `a remote figure that has moved still moves the bar`() {
        val local = PlayState.InProgress(0, hour)

        val state = accountAwarePlayState(
            local,
            remotePositionMs = hour / 2,
            remoteDurationMs = hour,
            remoteAlreadyUsedMs = 60_000,
        )

        assertEquals(PlayState.InProgress(hour / 2, hour), state)
    }

    @Test
    fun `a finished video restarted here shows the progress a tap resumes, not Played`() {
        val state = accountAwarePlayState(
            local = PlayState.InProgress(55_966, 5_806_000),
            remotePositionMs = 5_806_000,
            remoteDurationMs = 5_806_000,
            remoteAlreadyUsedMs = 5_806_000,
        )

        assertEquals(PlayState.InProgress(55_966, 5_806_000), state)
    }
}
