package com.dewijones92.totum.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.Refusal
import com.dewijones92.totum.live.startsAtText
import com.dewijones92.totum.playback.PlaybackProblem
import com.dewijones92.totum.playback.TrackKind
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.RefusedTap
import com.dewijones92.totum.targets.WayOut
import com.dewijones92.totum.video.LivePhase
import com.dewijones92.totum.video.LiveState
import com.dewijones92.totum.video.LiveWaitSchedule

private val shownTaps = DuplicateTaps()

@Composable
internal fun RefusedTapToasts(queue: PlaybackQueue, signedIn: Boolean, ways: RefusalWays) {
    val context = LocalContext.current
    val signedInNow by rememberUpdatedState(signedIn)
    val liveStates = LocalLiveStates.current
    val downloadStates = LocalDownloadStates.current
    val offers by rememberUpdatedState(LocalSnackbarOffers.current)
    val actions by rememberUpdatedState(LocalItemActions.current)
    val listenLabel by rememberUpdatedState(stringResource(R.string.play_audio_only))
    val collector = remember { Any() }
    LaunchedEffect(queue) {
        queue.refusedTaps.collect { tap ->
            val message = context.refusalMessage(
                tap,
                signedInNow,
                tap.videoId?.let { liveStates.value[it] }?.takeIf { it.phase == LivePhase.UPCOMING },
                reach = ways::reachOf,
            )
            val now = System.currentTimeMillis()
            if (shownTaps.isRepeat(message, now)) {
                Diag.log(
                    "ui",
                    "toast dropped, the same words were shown ${shownTaps.msSinceLast(now)}ms ago: $message " +
                        "[collector=${System.identityHashCode(collector)}]",
                )
                return@collect
            }
            val wayOut = ways.wayOut(tap) { id -> downloadStates.value[id] is DownloadState.Downloaded }
            val rows = actions
            val listen = tap.listen?.takeIf { rows?.audioMode == false }
            val shell = offers
            if (listen != null && shell != null && rows != null) {
                Diag.log("ui", "snackbar with 🎧 Listen: $message [collector=${System.identityHashCode(collector)}]")
                shell.offer(message, listenLabel, "listen", SnackbarDuration.Long) {
                    Diag.log("listen", "🎧 Listen tapped on the failure of ${listen.item.id.value}")
                    rows.switchMode(listen.item)
                }
            } else if (wayOut != null && shell != null && tap.item != null) {
                val item = tap.item
                val label = context.wayOutLabel(wayOut)
                Diag.log(
                    "ui",
                    "snackbar with a way out ($wayOut) for ${item.item.id.value}: $message " +
                        "[collector=${System.identityHashCode(collector)}]",
                )
                shell.offer(message, label, "way-out", SnackbarDuration.Long) {
                    Diag.log("ui", "way out taken for ${item.item.id.value}: $wayOut")
                    ways.take(wayOut, item)
                }
            } else {
                Diag.log("ui", "toast: $message [collector=${System.identityHashCode(collector)}]")
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun Context.wayOutLabel(way: WayOut): String = when (way) {
    is WayOut.PlayOn -> getString(R.string.refused_way_play_on, way.option.target.label)
    is WayOut.FetchCopy -> getString(R.string.refused_way_fetch_copy, way.maxHeight)
}

internal class DuplicateTaps(private val windowMs: Long = DUPLICATE_WINDOW_MS) {
    private var lastMessage: String? = null
    private var lastAt = 0L

    @Synchronized
    fun isRepeat(message: String, nowMs: Long): Boolean {
        val repeat = message == lastMessage && nowMs - lastAt < windowMs
        if (!repeat) {
            lastMessage = message
            lastAt = nowMs
        }
        return repeat
    }

    @Synchronized
    fun msSinceLast(nowMs: Long): Long = nowMs - lastAt

    private companion object {
        const val DUPLICATE_WINDOW_MS = 1_000L
    }
}

internal fun Context.refusalMessage(
    tap: RefusedTap,
    signedIn: Boolean,
    startsSoon: LiveState? = null,
    reach: (PlaybackProblem.CannotDecode) -> Int? = { null },
): String {
    if (startsSoon != null) return startsSoonMessage(tap.title, startsSoon)
    tap.problem?.let { return getString(R.string.refused_tap, tap.title, problemWords(it, reach)) }
    val why = getString(
        when (tap.reason) {
            Refusal.NotOnThisDevice -> R.string.refused_offline
            Refusal.StreamWillNotPlay -> R.string.refused_stream
            Refusal.NothingToPlay -> R.string.refused_nothing
            null -> if (signedIn) R.string.refused_no_stream else R.string.refused_no_stream_signed_out
        },
    )
    return getString(R.string.refused_tap, tap.title, why)
}

private fun Context.problemWords(problem: PlaybackProblem, reach: (PlaybackProblem.CannotDecode) -> Int?): String =
    when (problem) {
        is PlaybackProblem.CannotDecode -> decodeWords(problem, reach)
        is PlaybackProblem.Failed -> getString(R.string.refused_player_error, problem.errorName)
        is PlaybackProblem.NoTrackList -> getString(R.string.refused_no_track_list)
    }

private fun Context.decodeWords(
    problem: PlaybackProblem.CannotDecode,
    reach: (PlaybackProblem.CannotDecode) -> Int?,
): String {
    val video = problem.track == TrackKind.VIDEO
    val reaches = reach(problem)
    val width = problem.width
    val height = problem.height
    return when {
        problem.decoderStopped -> getString(
            if (video) R.string.refused_decoder_stopped_video else R.string.refused_decoder_stopped_sound,
            problem.format,
        )
        video && width != null && height != null && reaches != null && height > reaches ->
            getString(R.string.refused_beyond_device, problem.format, width, height, reaches)
        else -> getString(
            if (video) R.string.refused_cannot_decode_video else R.string.refused_cannot_decode_sound,
            problem.format,
        )
    }
}

private fun Context.startsSoonMessage(title: String, soon: LiveState): String {
    val at = soon.startsAt ?: return getString(R.string.refused_live_soon, title)
    val tooFar = !LiveWaitSchedule.worthWaitingFor(at, System.currentTimeMillis())
    return getString(if (tooFar) R.string.refused_live_far else R.string.refused_live_at, title, startsAtText(at))
}
