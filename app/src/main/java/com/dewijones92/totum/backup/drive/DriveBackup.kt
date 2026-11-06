package com.dewijones92.totum.backup.drive

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.BackupCodec
import com.dewijones92.totum.data.backup.BackupReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

sealed interface DriveBackupStatus {
    data object NotSetUp : DriveBackupStatus
    data class Done(val atMs: Long, val bytes: Int, val where: String) : DriveBackupStatus
    data class Failed(val atMs: Long, val reason: String, val where: String) : DriveBackupStatus
    data object Waiting : DriveBackupStatus
}

sealed interface BackupCheck {
    data object NotSetUp : BackupCheck
    data class Readable(val copy: BackupCopy, val where: String, val bytes: Int) : BackupCheck
    data class Unreadable(val why: String, val where: String?) : BackupCheck
}

class DriveBackup(
    private val service: BackupService,
    private val locations: BackupLocationStore,
    private val open: (BackupLocation) -> BackupFolder,
    private val phoneCopies: BackupFolder,
    private val today: () -> LocalDate = LocalDate::now,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableStatus = MutableStateFlow(
        if (locations.current == null) DriveBackupStatus.NotSetUp else DriveBackupStatus.Waiting,
    )
    val status: StateFlow<DriveBackupStatus> = mutableStatus.asStateFlow()
    private val writing = Mutex()

    val isSetUp: Boolean get() = locations.current != null

    var neverAsk: Boolean
        get() = locations.neverAsk
        set(value) {
            locations.neverAsk = value
        }

    fun diagnostics(): Map<String, String> = mapOf(
        "backup.location" to (locations.current?.let { if (it.isFolder) "folder" else "single file" } ?: "none"),
        "backup.neverAsk" to locations.neverAsk.toString(),
        "backup.status" to when (val s = status.value) {
            DriveBackupStatus.NotSetUp -> "not set up"
            DriveBackupStatus.Waiting -> "waiting for a change"
            is DriveBackupStatus.Done -> "done at ${java.time.Instant.ofEpochMilli(s.atMs)}, ${s.bytes}B"
            is DriveBackupStatus.Failed -> "FAILED at ${java.time.Instant.ofEpochMilli(s.atMs)}: ${s.reason}"
        },
    )

    suspend fun backUpNow(reason: String): Boolean = withContext(Dispatchers.IO) { backUpOnIo(reason) }

    private suspend fun backUpOnIo(reason: String): Boolean = writing.withLock {
        val location = locations.current ?: return@withLock false.also {
            Diag.log(TAG, "not backing up ($reason): no Drive location chosen")
        }
        val drive = open(location)
        runCatching {
            val text = BackupCodec.encode(service.create(includeQueue = false))
            drive.write(LATEST, text)
            val dailies = if (location.isFolder) drive else phoneCopies
            val todays = dailyName(today())
            if (dailies.list().none { it.name == todays }) dailies.write(todays, text)
            prune(dailies)
            text.length
        }.fold(
            onSuccess = { bytes ->
                mutableStatus.value = DriveBackupStatus.Done(now(), bytes, drive.where)
                Diag.log(TAG, "backed up ($reason): $bytes bytes to ${drive.where}")
                true
            },
            onFailure = { error ->
                mutableStatus.value = DriveBackupStatus.Failed(now(), error.toString(), drive.where)
                Diag.warn(TAG, "backup ($reason) to ${drive.where} failed: $error")
                false
            },
        )
    }

    suspend fun copies(location: BackupLocation? = locations.current): List<Pair<BackupCopy, BackupFolder>> {
        location ?: return emptyList()
        val drive = open(location)
        val there = runCatching { drive.list() }.getOrElse {
            Diag.warn(TAG, "could not list ${drive.where}: $it")
            emptyList()
        }.filter { it.name == LATEST || it.name.isDailyName() }.map { it to drive }
        val here = if (location.isFolder) {
            emptyList()
        } else {
            phoneCopies.list().filter {
                it.name.isDailyName()
            }.map { it to phoneCopies }
        }
        return (there + here).sortedByDescending { it.first.modifiedAtMs }
    }

    suspend fun checkLatest(why: String): BackupCheck = withContext(Dispatchers.IO) {
        val location = locations.current
            ?: return@withContext BackupCheck.NotSetUp.also { Diag.log(TAG, "backup check ($why): no Drive location") }
        val newest = copies(location).firstOrNull()
            ?: return@withContext BackupCheck.Unreadable("no backup copy was found", open(location).where).also {
                Diag.warn(TAG, "backup check ($why): no copy found at ${open(location).where}")
            }
        val (copy, folder) = newest
        val text = runCatching { folder.read(copy.name) }.getOrElse { error ->
            return@withContext BackupCheck.Unreadable("${copy.name} could not be opened: $error", folder.where).also {
                Diag.warn(TAG, "backup check ($why): ${copy.name} could not be opened from ${folder.where}: $error")
            }
        } ?: return@withContext BackupCheck.Unreadable("${copy.name} is empty or missing", folder.where).also {
            Diag.warn(TAG, "backup check ($why): ${copy.name} read back as nothing from ${folder.where}")
        }
        when (val parsed = withContext(Dispatchers.Default) { BackupCodec.decode(text) }) {
            is BackupReadResult.Ok -> BackupCheck.Readable(copy, folder.where, text.length).also {
                Diag.log(
                    TAG,
                    "dewidebug backup check ($why): ${copy.name} from ${folder.where} reads fine [${text.length}B " +
                        "modified=${java.time.Instant.ofEpochMilli(copy.modifiedAtMs)} " +
                        "subscriptions=${parsed.backup.subscriptions.size}]",
                )
            }
            is BackupReadResult.Unreadable -> BackupCheck.Unreadable(
                "${copy.name} is not a readable backup (${parsed.detail})",
                folder.where,
            ).also { Diag.warn(TAG, "backup check ($why): ${copy.name} from ${folder.where} unreadable: $parsed") }
            is BackupReadResult.TooNew -> BackupCheck.Unreadable(
                "${copy.name} comes from a newer Totum (backup version ${parsed.fileVersion}, this one reads " +
                    "${parsed.supported})",
                folder.where,
            ).also { Diag.warn(TAG, "backup check ($why): ${copy.name} from ${folder.where} too new: $parsed") }
        }
    }

    suspend fun restore(copy: BackupCopy, from: BackupFolder): BackupService.RestoreSummary? {
        val text = withContext(Dispatchers.IO) { from.read(copy.name) }
            ?: return null.also { Diag.warn(TAG, "restore: ${copy.name} unreadable") }
        return when (val parsed = withContext(Dispatchers.Default) { BackupCodec.decode(text) }) {
            is BackupReadResult.Ok -> service.restore(parsed.backup.copy(queue = emptyList())).also {
                Diag.log(TAG, "restored ${copy.name} from ${from.where}: $it")
            }
            else -> null.also { Diag.warn(TAG, "restore: ${copy.name} could not be read: $parsed") }
        }
    }

    fun chose(location: BackupLocation) {
        locations.set(location)
        mutableStatus.value = DriveBackupStatus.Waiting
        Diag.log(TAG, "backups now go to $location")
    }

    fun turnOff() {
        locations.set(null)
        mutableStatus.value = DriveBackupStatus.NotSetUp
        Diag.log(TAG, "Drive backup turned off")
    }

    private suspend fun prune(folder: BackupFolder) {
        folder.list().filter { it.name.isDailyName() }.sortedByDescending { it.name }.drop(KEEP_DAILY).forEach {
            folder.delete(it.name)
            Diag.log(TAG, "removed the old daily copy ${it.name} from ${folder.where}")
        }
    }

    companion object {
        const val LATEST = "totum-backup-latest.json"
        const val KEEP_DAILY = 7
        private const val TAG = "backup"

        fun dailyName(day: LocalDate): String = "totum-backup-$day.json"

        private fun String.isDailyName() = matches(Regex("""totum-backup-\d{4}-\d{2}-\d{2}\.json"""))
    }
}
