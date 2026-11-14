package com.dewijones92.totum.backup.drive

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.backup.BackupCodec
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
import java.io.File

class DriveBackupCheckTest {

    @get:Rule val temp = TemporaryFolder()

    private val restores = mutableListOf<Map<String, String>>()
    private val service = BackupService(
        subscriptions = InMemorySubscriptionStore(),
        playlists = InMemoryLocalPlaylistStore(),
        queueStore = InMemoryQueueStore(),
        progress = InMemoryPlaybackProgressStore(),
        settings = object : BackupService.BackupSettings {
            override fun export() = mapOf("k" to "v")
            override fun restore(values: Map<String, String>) {
                restores += values
            }
        },
        appVersion = "test",
    )

    private val directory: File by lazy { temp.newFolder() }
    private val folder: BackupFolder by lazy { LocalBackupFolder(directory) }
    private suspend fun goodText(): String = BackupCodec.encode(service.create(includeQueue = false))

    private fun driveOver(
        over: BackupFolder = folder,
        location: BackupLocation? = BackupLocation("content://drive/x", isFolder = true),
    ) = DriveBackup(
        service,
        InMemoryBackupLocationStore(location),
        open = { over },
        phoneCopies = LocalBackupFolder(temp.newFolder()),
    )

    private fun put(name: String, text: String, modifiedAtMs: Long) {
        File(directory, name).apply {
            writeText(text)
            setLastModified(modifiedAtMs)
        }
    }

    @Test
    fun `a good copy is readable, and the check names it, where it is and how big it is`() = runTest {
        put(DriveBackup.LATEST, goodText(), 5_000)

        val check = driveOver().checkLatest("test")

        check as BackupCheck.Readable
        assertEquals(DriveBackup.LATEST, check.copy.name)
        assertEquals(folder.where, check.where)
        assertEquals(goodText().length, check.bytes)
    }

    @Test
    fun `only the newest copy is read, so a corrupt old one does not fail a good new one`() = runTest {
        put("totum-backup-2026-11-01.json", "not a backup", 1_000)
        put(DriveBackup.LATEST, goodText(), 9_000)

        assertTrue(driveOver().checkLatest("test") is BackupCheck.Readable)
    }

    @Test
    fun `and a corrupt newest copy fails even when an older one is fine`() = runTest {
        put("totum-backup-2026-11-01.json", goodText(), 1_000)
        put(DriveBackup.LATEST, "{ truncated", 9_000)

        val check = driveOver().checkLatest("test")

        check as BackupCheck.Unreadable
        assertTrue(check.why, "${DriveBackup.LATEST} is not a readable backup" in check.why)
        assertEquals(folder.where, check.where)
    }

    @Test
    fun `a copy from a newer Totum is unreadable and says which versions`() = runTest {
        val newer = BackupCodec.encode(service.create(includeQueue = false).copy(version = Backup.CURRENT_VERSION + 1))
        put(DriveBackup.LATEST, newer, 5_000)

        val check = driveOver().checkLatest("test")

        check as BackupCheck.Unreadable
        assertTrue(check.why, "comes from a newer Totum" in check.why)
        assertTrue(check.why, "backup version ${Backup.CURRENT_VERSION + 1}" in check.why)
        assertTrue(check.why, "reads ${Backup.CURRENT_VERSION}" in check.why)
    }

    @Test
    fun `a folder with no copies is unreadable, with the reason in words`() = runTest {
        val check = driveOver().checkLatest("test")

        check as BackupCheck.Unreadable
        assertEquals("no backup copy was found", check.why)
    }

    @Test
    fun `files that are not backup copies are not mistaken for one`() = runTest {
        put("notes.txt", goodText(), 5_000)

        assertTrue(driveOver().checkLatest("test") is BackupCheck.Unreadable)
    }

    @Test
    fun `a copy that reads back as nothing is unreadable, not a crash`() = runTest {
        val vanishing = object : BackupFolder by folder {
            override suspend fun list() = listOf(BackupCopy(DriveBackup.LATEST, 5_000))

            override suspend fun read(name: String): String? = null
        }

        val check = driveOver(vanishing).checkLatest("test")

        check as BackupCheck.Unreadable
        assertEquals("${DriveBackup.LATEST} is empty or missing", check.why)
    }

    @Test
    fun `a copy that cannot be opened is unreadable and carries the error`() = runTest {
        val broken = object : BackupFolder by folder {
            override suspend fun list() = listOf(BackupCopy(DriveBackup.LATEST, 5_000))

            override suspend fun read(name: String): String? = error("permission revoked")
        }

        val check = driveOver(broken).checkLatest("test")

        check as BackupCheck.Unreadable
        assertTrue(check.why, "could not be opened" in check.why && "permission revoked" in check.why)
    }

    @Test
    fun `a folder that cannot even be listed is the same as one with no copies`() = runTest {
        val unlistable = object : BackupFolder by folder {
            override suspend fun list(): List<BackupCopy> = error("offline")
        }

        val check = driveOver(unlistable).checkLatest("test")

        check as BackupCheck.Unreadable
        assertEquals("no backup copy was found", check.why)
    }

    @Test
    fun `with no Drive location chosen the check does not apply`() = runTest {
        assertEquals(BackupCheck.NotSetUp, driveOver(location = null).checkLatest("test"))
    }

    @Test
    fun `checking never restores anything`() = runTest {
        put(DriveBackup.LATEST, goodText(), 5_000)

        driveOver().checkLatest("test")

        assertTrue(restores.isEmpty())
    }
}
