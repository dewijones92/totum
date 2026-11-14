package com.dewijones92.totum.diagnostics

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Pending reports on disk. Written before any upload is attempted, because a crash is
 * usually the last thing the process does — a report held only in memory, or lost
 * mid-request, is a report you never see. Files are deleted only once the server has
 * accepted them, so a crash with no connectivity still arrives later.
 */
internal object DiagnosticsStore {

    private const val MAX_PENDING = 50

    private fun dir(context: Context): File =
        File(context.filesDir, "diagnostics").apply { mkdirs() }

    fun write(context: Context, report: JSONObject): File {
        val file = File(dir(context), "${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
        file.writeText(report.toString())
        prune(context)
        return file
    }

    fun pending(context: Context): List<File> =
        dir(context).listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()

    /** Keeps the newest; a device offline for weeks shouldn't hoard reports forever. */
    private fun prune(context: Context) {
        val files = pending(context)
        files.take((files.size - MAX_PENDING).coerceAtLeast(0)).forEach {
            it.delete()
            ReportReceipt(it).delete()
        }
    }
}

/** A stable per-install id, so reports from one device can be grouped. */
internal object InstallId {
    private const val PREFS = "totum_diagnostics"
    private const val KEY = "install_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit { putString(KEY, id) }
        }
    }
}
