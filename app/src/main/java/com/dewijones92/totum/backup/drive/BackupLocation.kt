package com.dewijones92.totum.backup.drive

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag

data class BackupLocation(val uri: String, val isFolder: Boolean) {
    override fun toString(): String = "${if (isFolder) "the folder" else "the file"} ${Uri.decode(
        uri
    ).substringAfterLast(':')}"
}

interface BackupLocationStore {
    val current: BackupLocation?
    var neverAsk: Boolean

    fun set(location: BackupLocation?)
}

class InMemoryBackupLocationStore(override var current: BackupLocation? = null) : BackupLocationStore {
    override var neverAsk: Boolean = false

    override fun set(location: BackupLocation?) {
        current = location
    }
}

class SharedPrefsBackupLocationStore(context: Context) : BackupLocationStore {
    private val prefs = context.getSharedPreferences("totum_drive_backup", Context.MODE_PRIVATE)

    override val current: BackupLocation?
        get() = prefs.getString(KEY_URI, null)?.let { BackupLocation(it, prefs.getBoolean(KEY_FOLDER, true)) }

    override var neverAsk: Boolean
        get() = prefs.getBoolean(KEY_NEVER_ASK, false)
        set(value) {
            prefs.edit { putBoolean(KEY_NEVER_ASK, value) }
            Diag.log("backup", "never ask about Drive backup: $value")
        }

    override fun set(location: BackupLocation?) {
        prefs.edit {
            if (location == null) {
                remove(
                    KEY_URI
                )
            } else {
                putString(KEY_URI, location.uri).putBoolean(KEY_FOLDER, location.isFolder)
            }
        }
    }

    private companion object {
        const val KEY_URI = "uri"
        const val KEY_FOLDER = "isFolder"
        const val KEY_NEVER_ASK = "neverAsk"
    }
}
