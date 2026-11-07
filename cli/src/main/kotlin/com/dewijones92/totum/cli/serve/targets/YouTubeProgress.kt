package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetReporting
import com.dewijones92.totum.innertube.history.YouTubeWatchHistory

internal class YouTubeProgress(
    private val history: YouTubeWatchHistory,
    private val signedIn: suspend () -> Boolean,
) : ProgressReporter {

    private val begun = HashSet<String>()

    override suspend fun report(entry: TargetEntry, positionMs: Long, durationMs: Long?, finished: Boolean) {
        val signed = signedIn()
        val id = TargetReporting.videoIdToReport(entry, signed)
        if (id == null) {
            Diag.log("targets", "${entry.item.itemId}: not reported to YouTube, ${TargetReporting.why(entry, signed)}")
            return
        }
        if (durationMs == null || durationMs <= 0) {
            Diag.log("targets", "$id: not reported to YouTube, length unknown (position ${positionMs}ms)")
            return
        }
        if (begun.add(id)) {
            Diag.log("targets", "$id: YouTube session -> ${history.beginSession(id)}")
        }
        val result = history.reportProgress(id, positionMs / MS, durationMs / MS, finished)
        Diag.log(
            "targets",
            "$id: reported to YouTube ${positionMs}ms of ${durationMs}ms finished=$finished added by " +
                "${entry.addedBy} -> $result",
        )
    }

    private companion object {
        const val MS = 1_000f
    }
}
