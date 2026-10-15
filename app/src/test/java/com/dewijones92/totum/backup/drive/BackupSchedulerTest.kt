package com.dewijones92.totum.backup.drive

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSchedulerTest {

    private val backups = mutableListOf<Pair<Long, String>>()
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    private fun TestScope.scheduler() = BackupScheduler(
        scope = backgroundScope,
        backUp = { why -> backups += testScheduler.currentTime to why },
        now = { testScheduler.currentTime },
    ).also {
        it.follow(changes)
        runCurrent()
    }

    @Test
    fun `a burst of changes is backed up once, thirty seconds after it settles`() = runTest {
        scheduler()
        changes.emit("subscriptions")
        advanceTimeBy(10_000)
        changes.emit("playlists")
        advanceTimeBy(29_000)
        assertEquals(emptyList<Pair<Long, String>>(), backups)
        advanceTimeBy(2_000)
        assertEquals(listOf(40_000L to "subscriptions, playlists"), backups)
    }

    @Test
    fun `changes that never settle are backed up at most five minutes after the first`() = runTest {
        scheduler()
        advanceTimeBy(5_000)
        repeat(40) {
            changes.emit("progress")
            advanceTimeBy(10_000)
        }
        advanceTimeBy(60_000)
        assertEquals(listOf(305_000L, 425_000L), backups.map { it.first })
    }

    @Test
    fun `nothing changes, nothing is written`() = runTest {
        scheduler()
        advanceTimeBy(3_600_000)
        assertEquals(emptyList<Pair<Long, String>>(), backups)
    }
}
