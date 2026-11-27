package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountRepairTest {

    private val length = 600_000L

    @Test
    fun `an item Played here that the account reads at the 10 percent floor is finished again`() {
        val repair = accountRepairFor(PlayState.Played, 60_000, length, reconciledMs = null)

        assertEquals(AccountRepair.Finish(durationMs = length, accountFigureMs = 60_000), repair)
    }

    @Test
    fun `an item Played here that the account already reads at the end needs nothing`() {
        assertNull(accountRepairFor(PlayState.Played, length, length, reconciledMs = null))
    }

    @Test
    fun `an item Played here that the account reads at 99 percent is finished too`() {
        assertEquals(
            AccountRepair.Finish(length, 594_000),
            accountRepairFor(PlayState.Played, 594_000, length, reconciledMs = null),
        )
    }

    @Test
    fun `an item in progress here and well ahead of the account has its position re-sent`() {
        val repair = accountRepairFor(PlayState.InProgress(300_000, length), 60_000, length, reconciledMs = null)

        assertEquals(AccountRepair.Position(300_000, length, 60_000), repair)
    }

    @Test
    fun `an item in progress here only a percent or a minute ahead is left to the pings`() {
        assertNull(accountRepairFor(PlayState.InProgress(100_000, length), 60_000, length, reconciledMs = null))
    }

    @Test
    fun `an item the account is ahead on is not repaired, the account wins that one`() {
        assertNull(accountRepairFor(PlayState.InProgress(60_000, length), 300_000, length, reconciledMs = null))
    }

    @Test
    fun `an item never played here is not repaired`() {
        assertNull(accountRepairFor(PlayState.Unplayed, 60_000, length, reconciledMs = null))
        assertNull(accountRepairFor(null, 60_000, length, reconciledMs = null))
    }

    @Test
    fun `a figure the account holds that Totum already acted on is repaired once more only if unmoved`() {
        assertEquals(
            AccountRepair.Finish(length, 60_000),
            accountRepairFor(PlayState.Played, 60_000, length, reconciledMs = 60_000),
        )
    }

    @Test
    fun `an account figure that moved since Totum acted is another device's newer play and is left alone`() {
        assertNull(accountRepairFor(PlayState.Played, 180_000, length, reconciledMs = 60_000))
    }

    @Test
    fun `after a repair the figure acted on is the one sent, so the same read does not repair twice`() {
        val first = accountRepairFor(PlayState.Played, 60_000, length, reconciledMs = null)!!

        assertNull(accountRepairFor(PlayState.Played, 60_000, length, reconciledMs = first.actedOnMs))
    }

    @Test
    fun `an account with no usable length cannot be compared`() {
        assertNull(accountRepairFor(PlayState.Played, 60_000, 0, reconciledMs = null))
    }
}
