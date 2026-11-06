package com.dewijones92.totum.data.backup

import com.dewijones92.totum.common.LegacyLinkToken
import kotlinx.serialization.json.Json

/**
 * Reads and writes the [Backup] file. Separate from the service that gathers and applies
 * it, so the format can be tested without a database behind it.
 */
public object BackupCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(backup: Backup): String =
        LegacyLinkToken.strip(json.encodeToString(Backup.serializer(), backup), "a backup being written")

    /**
     * Parses a backup, or explains why it can't. Never throws: this is arbitrary bytes a
     * user pointed at us, and "that isn't a Totum backup" is an answer, not a crash.
     */
    public fun decode(content: String): BackupReadResult {
        val backup = runCatching {
            json.decodeFromString(Backup.serializer(), LegacyLinkToken.strip(content, "a backup being read"))
        }
            .getOrElse { return BackupReadResult.Unreadable(it.message ?: "not a backup file") }

        // Refused rather than partially applied: a file from a newer build may hold
        // sections this build does not know to restore, and a partial restore that
        // reported success would be worse than a clear refusal.
        if (backup.version > Backup.CURRENT_VERSION) {
            return BackupReadResult.TooNew(backup.version, Backup.CURRENT_VERSION)
        }
        return BackupReadResult.Ok(backup)
    }
}

public sealed interface BackupReadResult {
    public data class Ok(val backup: Backup) : BackupReadResult
    public data class Unreadable(val detail: String) : BackupReadResult
    public data class TooNew(val fileVersion: Int, val supported: Int) : BackupReadResult
}
