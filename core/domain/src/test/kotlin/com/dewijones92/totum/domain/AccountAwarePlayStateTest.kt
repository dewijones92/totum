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
}
