package com.dewijones92.totum.diagnostics

import android.content.Context
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.Diag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

internal class FlightRecorder(
    private val dir: File,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) : Diag.Recorder {

    private val lastSavedByKind = mutableMapOf<String, Long>()

    @Synchronized
    override fun save(kind: String, detail: String, trail: List<Breadcrumbs.Entry>) {
        val at = now()
        val safeKind = kind.lowercase().replace(NOT_A_NAME_CHARACTER, "-")
        val since = lastSavedByKind[safeKind]?.let { at - it }
        if (since != null && since < SAME_KIND_COOLDOWN_MS) {
            Diag.log("diagnostics", "flight recorder: $safeKind not saved again, one was saved ${since}ms ago")
            return
        }
        lastSavedByKind[safeKind] = at
        dir.mkdirs()
        val snapshot = snapshotOf(safeKind, detail, trail, at)
        val file = File(dir, "$at-$safeKind.json")
        val partial = File(dir, "$at-$safeKind.tmp")
        partial.writeText(snapshot.toString())
        check(partial.renameTo(file)) { "could not move ${partial.name} to ${file.name}" }
        val removed = prune(at)
        Diag.log(
            "diagnostics",
            "flight recorder: saved ${file.name} (${file.length()} bytes, ${snapshot.getInt("entriesKept")} of " +
                "${trail.size} trail lines) for: $detail; pruned $removed old snapshot(s)",
        )
    }

    private fun snapshotOf(kind: String, detail: String, trail: List<Breadcrumbs.Entry>, at: Long): JSONObject {
        var budget = MAX_SNAPSHOT_CHARS
        val newestFirst = trail.asReversed().takeWhile { entry ->
            budget -= entry.message.take(MAX_MESSAGE_CHARS).length + entry.tag.length + LINE_OVERHEAD_CHARS
            budget >= 0
        }
        val events = JSONArray()
        newestFirst.asReversed().forEach { entry ->
            events.put(
                JSONObject()
                    .put("at", Instant.ofEpochMilli(entry.atEpochMs).toString())
                    .put("tag", entry.tag)
                    .put("message", entry.message.take(MAX_MESSAGE_CHARS)),
            )
        }
        return JSONObject()
            .put("kind", kind)
            .put("detail", detail)
            .put("savedAt", Instant.ofEpochMilli(at).toString())
            .put("appVersion", appVersion)
            .put("trailLines", trail.size)
            .put("entriesKept", newestFirst.size)
            .put("events", events)
    }

    private fun prune(at: Long): Int {
        val files = filesIn(dir)
        val stale = files.filter { at - (it.name.substringBefore('-').toLongOrNull() ?: 0L) > MAX_AGE_MS }
        val surplus = (files - stale.toSet()).dropLast(MAX_SNAPSHOTS)
        (stale + surplus).forEach { it.delete() }
        dir.listFiles { f -> f.extension == "tmp" }?.forEach { it.delete() }
        return stale.size + surplus.size
    }

    companion object {
        const val MAX_SNAPSHOTS = 5
        const val MAX_SNAPSHOT_CHARS = 200_000
        const val MAX_REPORT_CHARS = 1_000_000
        const val MAX_MESSAGE_CHARS = 600
        const val LINE_OVERHEAD_CHARS = 60
        const val SAME_KIND_COOLDOWN_MS = 2 * 60 * 1_000L
        const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1_000L
        private val NOT_A_NAME_CHARACTER = Regex("[^a-z0-9]+")

        fun dirIn(context: Context): File = File(context.filesDir, "flight-recorder")

        private fun filesIn(dir: File): List<File> =
            dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()

        fun forReport(dir: File): JSONArray {
            val array = JSONArray()
            var budget = MAX_REPORT_CHARS
            filesIn(dir).asReversed().forEach { file ->
                if (file.length() > budget) {
                    Diag.log(
                        "diagnostics",
                        "flight recorder: ${file.name} left out of the report, over its size budget"
                    )
                    return@forEach
                }
                runCatching { JSONObject(file.readText()).put("file", file.name) }
                    .onSuccess {
                        budget -= file.length().toInt()
                        array.put(it)
                    }
                    .onFailure { Diag.warn("diagnostics", "flight recorder: ${file.name} could not be read", it) }
            }
            return array
        }
    }
}

internal fun installFlightRecorder(context: Context, appVersion: String) {
    Diag.recorder = FlightRecorder(FlightRecorder.dirIn(context), appVersion)
}
