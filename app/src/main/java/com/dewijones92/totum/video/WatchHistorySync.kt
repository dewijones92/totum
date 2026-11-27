package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.AccountProgressOutbox
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PendingAccountProgress
import com.dewijones92.totum.domain.isNearlyFinished
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Mirrors video watch-progress up to YouTube's servers (History + cross-device
 * resume, and the recommendations that follow from them) as playback advances —
 * the account-side counterpart to the app's local resume. Records on a new
 * video, on finishing, and roughly every [REPORT_INTERVAL_MS]; a finished video
 * is recorded once.
 *
 * **Records, never sends.** Each due update goes into the [AccountProgressOutbox] and the
 * [ProgressOutboxDrain] is kicked. Until 2026-09-06 the ping went out from here and its result was
 * dropped, so listening with no network — or during the weeks YouTube refused this app a session —
 * was lost outright. Now it is held until it can go, and the drain is the only thing that talks to
 * the account.
 *
 * **Gated on the PILLAR, not on whether a video track is present.** It used to test
 * `hasVideo`, which excluded every YouTube video played in audio-only mode — "Listen",
 * and anything the queue had pre-downloaded as audio. With auto-download-audio on by
 * default that is most listening, so the bulk of what Dewi watched was invisible to his
 * own YouTube account and fed nothing back to the algorithm. Whether a picture is being
 * rendered has no bearing on whether YouTube should be told you watched something.
 */
internal class WatchHistorySync(
    private val playback: PlaybackController,
    private val outbox: AccountProgressOutbox,
    private val drain: ProgressOutboxDrain,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val held: () -> Boolean = { false },
) {
    private var lastVideoId: String? = null
    private var lastReportMs = 0L
    private var finishedVideoId: String? = null
    private var run: ItemRun? = null

    fun start() {
        scope.launch { playback.state.collect { state -> if (state != null) onState(state) } }
    }

    private fun onState(state: PlaybackState) {
        val videoId = state.itemId.value
        run?.takeIf { it.videoId != videoId }?.let {
            noteLeft(it)
            run = null
        }
        val durationMs = reportableDurationMs(state) ?: return
        val finished = isNearlyFinished(state.positionMs, durationMs)
        if (videoId != finishedVideoId || !finished) finishedVideoId = null
        if (finished && videoId == finishedVideoId) return

        val current = run?.takeIf { it.videoId == videoId } ?: ItemRun(videoId).also { run = it }
        current.saw(state.positionMs, durationMs, finished)

        val due = videoId != lastVideoId || finished || now() - lastReportMs >= REPORT_INTERVAL_MS
        if (!due || !allowed(videoId)) return
        record(state, durationMs, finished, firstForVideo = videoId != lastVideoId)
        current.recorded++
    }

    private fun reportableDurationMs(state: PlaybackState): Long? {
        // Podcasts are not YouTube's business; a YouTube video is, picture or not.
        if (state.kind != MediaKind.VIDEO) {
            decline(state.itemId.value, "it is a ${state.kind}")
            return null
        }
        val durationMs = state.durationMs ?: 0L
        if (durationMs <= 0L) {
            decline(state.itemId.value, "no duration known yet")
            return null
        }
        return durationMs
    }

    private fun allowed(videoId: String): Boolean {
        if (held()) {
            decline(videoId, "a test run on this device holds reports to the YouTube account")
            return false
        }
        val refusal = drain.refusalFor(videoId) ?: return true
        decline(videoId, "YouTube refuses reports for it for good ($refusal)")
        return false
    }

    private fun record(state: PlaybackState, durationMs: Long, finished: Boolean, firstForVideo: Boolean) {
        val videoId = state.itemId.value
        lastVideoId = videoId
        lastReportMs = now()
        if (finished) finishedVideoId = videoId
        val update = PendingAccountProgress(
            itemId = state.itemId,
            positionMs = state.positionMs,
            durationMs = durationMs,
            finished = finished,
            recordedAtEpochMs = now(),
        )
        // Off the 500ms state stream: the record is a database write and the drain a network call.
        scope.launch {
            outbox.record(update)
            // The first and the finish are the two moments worth a line; the routine ones in
            // between are the drain's to count.
            if (firstForVideo || finished) {
                Diag.log("yt-sync", "$videoId pos=${update.positionMs}ms fin=$finished — recorded for the account")
            }
            drain.kick()
        }
    }

    private class ItemRun(val videoId: String) {
        var recorded = 0
        var positionMs = 0L
        var durationMs = 0L
        var atTheEnd = false

        fun saw(positionMs: Long, durationMs: Long, finished: Boolean) {
            this.positionMs = positionMs
            this.durationMs = durationMs
            atTheEnd = finished
        }
    }

    private fun noteLeft(run: ItemRun) {
        val where = "recorded=${run.recorded} lastPos=${run.positionMs}ms of ${run.durationMs}ms " +
            "(${run.positionMs * PERCENT / run.durationMs}%)"
        val final = if (run.atTheEnd) "recorded, the outbox sends it" else "none"
        Diag.log("yt-sync-item", "${run.videoId}: left with final=$final [$where]")
    }

    private var declined: String? = null

    /**
     * Says when nothing will be reported, and why — once per item, not per state emission.
     *
     * Both of these used to be silent `return`s, and that silence is exactly why the pillar bug
     * survived for weeks: a downloaded YouTube video was skipped as "a PODCAST" and no line
     * anywhere said so. Once per item because the state stream ticks twice a second and a live
     * stream never learns its duration, which would be a flood.
     */
    private fun decline(itemId: String, why: String) {
        val note = "$itemId|$why"
        if (declined == note) return
        declined = note
        Diag.log("yt-sync", "not reporting $itemId to YouTube: $why")
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 15_000L
        const val PERCENT = 100L
    }
}
