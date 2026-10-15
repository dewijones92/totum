package com.dewijones92.totum.backup.drive

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.data.playlist.fake.InMemoryLocalPlaylistStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.subscription.fake.InMemorySubscriptionStore
import com.dewijones92.totum.playback.fake.InMemoryPlaybackProgressStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class DriveBackupTest {

    @get:Rule val temp = TemporaryFolder()

    private var day = LocalDate.of(2026, 10, 15)
    private val service = BackupService(
        subscriptions = InMemorySubscriptionStore(),
        playlists = InMemoryLocalPlaylistStore(),
        queueStore = InMemoryQueueStore(),
        progress = InMemoryPlaybackProgressStore(),
        settings = object : BackupService.BackupSettings {
            override fun export() = mapOf("k" to "v")
            override fun restore(values: Map<String, String>) = Unit
        },
        appVersion = "test",
    )

    private fun backup(folder: Boolean): Triple<DriveBackup, LocalBackupFolder, LocalBackupFolder> {
        val drive = LocalBackupFolder(temp.newFolder())
        val phone = LocalBackupFolder(temp.newFolder())
        val locations = InMemoryBackupLocationStore(BackupLocation("content://drive/x", isFolder = folder))
        return Triple(
            DriveBackup(service, locations, open = { drive }, phoneCopies = phone, today = { day }),
            drive,
            phone
        )
    }

    @Test
    fun `a folder keeps the latest and one copy a day, seven days at most`() = runTest {
        val (backup, drive, phone) = backup(folder = true)
        repeat(9) {
            assertTrue(backup.backUpNow("test"))
            backup.backUpNow("same day again")
            day = day.plusDays(1)
        }
        val names = drive.list().map { it.name }.sorted()
        assertEquals(DriveBackup.LATEST, names.last())
        assertEquals(
            (2..8).map { "totum-backup-${LocalDate.of(2026, 10, 15).plusDays(it.toLong())}.json" },
            names.dropLast(1)
        )
        assertEquals(emptyList<BackupCopy>(), phone.list())
    }

    @Test
    fun `a single Drive file holds the latest and the daily copies stay on the phone`() = runTest {
        val (backup, drive, phone) = backup(folder = false)
        backup.backUpNow("test")
        assertEquals(listOf(DriveBackup.LATEST), drive.list().map { it.name })
        assertEquals(listOf("totum-backup-2026-10-15.json"), phone.list().map { it.name })
    }

    @Test
    fun `not set up, nothing is written`() = runTest {
        val drive = LocalBackupFolder(temp.newFolder())
        val backup = DriveBackup(service, InMemoryBackupLocationStore(), open = { drive }, phoneCopies = drive)
        assertEquals(false, backup.backUpNow("test"))
        assertEquals(emptyList<BackupCopy>(), drive.list())
    }

    @Test
    fun `a copy can be restored`() = runTest {
        val (backup, drive, _) = backup(folder = true)
        backup.backUpNow("test")
        val (copy, from) = backup.copies().first()
        assertTrue(backup.restore(copy, from) != null)
        assertEquals(drive.where, from.where)
    }
}
