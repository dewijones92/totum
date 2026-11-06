package com.dewijones92.totum.live

import android.content.Context
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeWatchUrl
import com.dewijones92.totum.innertube.player.LiveCheck
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.tracking
import com.dewijones92.totum.queue.RefusedTap
import com.dewijones92.totum.video.LivePhase
import com.dewijones92.totum.video.LiveStates
import com.dewijones92.totum.video.LiveWaitSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class LiveStartWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val lives: LiveStates,
    private val check: suspend (HttpUrl) -> LiveCheck,
    private val operations: Operations,
    private val notices: LiveNotices,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val waiting = ConcurrentHashMap<String, Boolean>()

    fun follow(refused: Flow<RefusedTap>) = scope.launch {
        refused.collect { tap ->
            val soon = tap.videoId?.let(lives::of)?.takeIf { it.phase == LivePhase.UPCOMING }
            if (tap.videoId == null || soon == null) {
                Diag.log("live", "\"${tap.title}\" was refused for another reason; not waiting for it to go live")
                return@collect
            }
            if (!LiveWaitSchedule.worthWaitingFor(soon.startsAt, clock())) {
                Diag.log(
                    "live",
                    "\"${tap.title}\" starts ${soon.startsAt}, more than " +
                        "${LiveWaitSchedule.ONLY_WAITS_AHEAD_MS / MS_PER_HOUR} h away; not waiting for it",
                )
                return@collect
            }
            waitFor(tap.videoId, tap.title)
        }
    }

    fun waitFor(videoId: String, title: String) {
        val watchUrl = youTubeWatchUrl(videoId) ?: return
        if (waiting.putIfAbsent(videoId, true) != null) {
            Diag.log("live", "$videoId: already waiting for \"$title\" to go live; not starting a second wait")
            return
        }
        scope.launch {
            try {
                val subject = context.getString(R.string.live_wait_subject, title)
                operations.tracking("live-wait:$videoId", OperationKind.TASK, subject) { _ ->
                    waitUntilLive(videoId, title, watchUrl)
                }
            } finally {
                waiting.remove(videoId)
            }
        }
    }

    private suspend fun waitUntilLive(videoId: String, title: String, watchUrl: HttpUrl): OperationEnd {
        val began = clock()
        var checks = 0
        Diag.log("live", "$videoId: waiting for \"$title\" to go live (starts ${lives.of(videoId)?.startsAt ?: "?"})")
        while (true) {
            val startsAt = lives.of(videoId)?.startsAt
            val waited = clock() - began
            val inMs = LiveWaitSchedule.nextCheckInMs(checks, waited, startsAt, clock())
            if (inMs == null) {
                val hours = (waited / MS_PER_HOUR).toInt()
                Diag.warn(
                    "live",
                    "$videoId: gave up on \"$title\" after $checks checks over ${waited / MS_PER_MINUTE} min",
                )
                lives.cleared(videoId, "gave up waiting")
                notices.gaveUp(videoId, title, hours)
                return OperationEnd.Failed("it had not started after $hours hours")
            }
            Diag.log(
                "live",
                "$videoId: check #${checks + 1} in ${inMs / MS_PER_SECOND}s (waited ${waited / MS_PER_MINUTE} min)",
            )
            delay(inMs)
            checks++
            when (val outcome = check(watchUrl)) {
                is LiveCheck.Live -> {
                    Diag.log("live", "$videoId: \"$title\" is live after $checks checks (via ${outcome.via})")
                    lives.noteLive(videoId, "the wait found it live")
                    notices.isLive(videoId, title, watchUrl)
                    return OperationEnd.Done(1)
                }
                is LiveCheck.Ended -> {
                    Diag.log(
                        "live",
                        "$videoId: \"$title\" is playable but not live after $checks checks (via ${outcome.via}); " +
                            "it ended or is a recording; telling you it can be played",
                    )
                    lives.noteEnded(videoId, "the wait found a recording")
                    notices.isLive(videoId, title, watchUrl)
                    return OperationEnd.Done(1)
                }
                is LiveCheck.StillUpcoming -> Diag.log(
                    "live",
                    "$videoId: check #$checks says not yet (${outcome.upcoming.said}, via ${outcome.via})",
                )
                is LiveCheck.Unknown -> Diag.log(
                    "live",
                    "$videoId: check #$checks could not tell (${outcome.why}); will ask again",
                )
            }
        }
    }

    private companion object {
        const val MS_PER_SECOND = 1_000L
        const val MS_PER_MINUTE = 60_000L
        const val MS_PER_HOUR = 60 * MS_PER_MINUTE
    }
}
