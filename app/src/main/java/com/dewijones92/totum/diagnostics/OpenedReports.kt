package com.dewijones92.totum.diagnostics

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.JsonPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class OpenedReport(val atEpochMs: Long, val title: String)

object OpenedReportsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(OpenedReport.serializer())
    const val KEEP = 20

    fun encode(reports: List<OpenedReport>): String = json.encodeToString(list, reports)

    fun decode(text: String): List<OpenedReport> = json.decodeFromString(list, text)

    fun added(existing: List<OpenedReport>, report: OpenedReport): List<OpenedReport> =
        (listOf(report) + existing).take(KEEP)
}

class OpenedReports(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = JsonPrefs(context.applicationContext, "totum_opened_reports", "diagnostics")

    fun recent(): List<OpenedReport> = prefs.read(KEY, emptyList(), OpenedReportsCodec::decode)

    fun record(title: String) {
        val all = OpenedReportsCodec.added(recent(), OpenedReport(now(), title))
        prefs.write(KEY, OpenedReportsCodec.encode(all))
        Diag.log("diagnostics", "remembered that a GitHub report was opened; ${all.size} remembered on this phone")
    }

    private companion object {
        const val KEY = "opened"
    }
}
