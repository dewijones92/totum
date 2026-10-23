package com.dewijones92.totum.playback

import androidx.media3.common.Player
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.SourceSkips
import kotlin.math.abs

internal fun rewindAfterPauseMs(pausedMs: Long): Long = when {
    pausedMs < QUICK_PAUSE_MS -> 0
    pausedMs >= LONG_PAUSE_MS -> MOST_MS
    else -> LEAST_MS + (pausedMs - QUICK_PAUSE_MS) * (MOST_MS - LEAST_MS) / (LONG_PAUSE_MS - QUICK_PAUSE_MS)
}

internal data class ResumeFacts(val talk: Boolean, val live: Boolean, val enabled: Boolean)

internal data class StartChoice(val positionMs: Long, val why: String)

internal fun rewoundStart(positionMs: Long, pausedMs: Long, facts: ResumeFacts): StartChoice {
    val notRewound = when {
        !facts.enabled -> "rewind on resume is off in Settings"
        !facts.talk -> "music plays on from where it paused"
        facts.live -> "a live stream has nothing to rewind into"
        positionMs <= 0 -> "it is at the start"
        pausedMs < QUICK_PAUSE_MS -> "a quick pause"
        else -> null
    }
    if (notRewound != null) return StartChoice(positionMs, "paused ${pausedMs}ms, no rewind: $notRewound")
    val target = (positionMs - rewindAfterPauseMs(pausedMs)).coerceAtLeast(0)
    return StartChoice(target, "paused ${pausedMs}ms, rewound ${positionMs - target}ms from ${positionMs}ms")
}

internal data class SavedPlace(val positionMs: Long?, val savedAtEpochMs: Long?)

internal fun startFor(saved: SavedPlace, nowEpochMs: Long, facts: ResumeFacts, introEndMs: Long?): StartChoice {
    if (facts.live) {
        return StartChoice(
            0,
            "live: from the edge of the stream, never a saved place (${saved.positionMs}ms)"
        )
    }
    val resumed = when {
        saved.positionMs == null -> StartChoice(0, "nothing saved, from the start")
        saved.savedAtEpochMs == null -> StartChoice(
            saved.positionMs,
            "saved with no time (a shuffle start), not rewound"
        )
        else -> rewoundStart(saved.positionMs, nowEpochMs - saved.savedAtEpochMs, facts)
    }
    if (introEndMs == null || resumed.positionMs >= introEndMs) return resumed
    return StartChoice(introEndMs, "${resumed.why}; then past the show's intro to ${introEndMs}ms")
}

@Suppress("LongParameterList")
internal suspend fun startChoiceFor(
    item: MediaItem,
    tuned: ItemTuning,
    progress: PlaybackProgressStore,
    rewindOn: Boolean,
    skips: SourceSkips?,
    nowEpochMs: Long,
): StartChoice {
    val saved = progress.resumePositionMs(item.id)
    val place = SavedPlace(saved, saved?.let { progress.lastSavedAtEpochMs(item.id) })
    val facts = ResumeFacts(
        talk = tuned.keepsChanges,
        live = item.contentKind == MediaContentKind.LIVE,
        enabled = rewindOn
    )
    val length = item.duration?.inWholeMilliseconds
    val intro = length?.let { skips?.introStartFor(it) }
    if (skips?.introEndMs != null && length == null) {
        Diag.log("playback", "${item.id.value}: its length is not known yet, so the intro is skipped by the ticker")
    }
    return startFor(place, nowEpochMs, facts, intro)
}

internal data class PlayerMoment(
    val itemId: String,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val ended: Boolean,
    val focusLost: Boolean,
)

internal class ResumeRewind(private val now: () -> Long) {
    private var paused: Triple<String, Long, Long>? = null

    fun forget() {
        paused = null
    }

    fun replacedBy(itemId: String) {
        if (paused?.first != itemId) paused = null
    }

    fun onChange(moment: PlayerMoment, endOfItem: Boolean, facts: ResumeFacts): Long? {
        val stopped = !moment.playWhenReady || moment.focusLost
        return when {
            moment.ended -> null.also { forget() }
            stopped && !endOfItem -> null.also { notePause(moment) }
            stopped -> null
            else -> onResumed(moment.itemId, moment.positionMs, facts)
        }
    }

    private fun notePause(moment: PlayerMoment) {
        if (paused == null) paused = Triple(moment.itemId, moment.positionMs, now())
    }

    private fun onResumed(itemId: String, positionMs: Long, facts: ResumeFacts): Long? {
        val (pausedItem, pausedAt, since) = paused ?: return null
        paused = null
        val pausedMs = now() - since
        val moved = when {
            pausedItem != itemId -> "a different item is playing now"
            abs(positionMs - pausedAt) > MOVED_MS -> "it was moved while paused"
            else -> null
        }
        if (moved != null) {
            if (pausedMs >= QUICK_PAUSE_MS) {
                Diag.log(
                    "playback",
                    "resumed $itemId after ${pausedMs}ms: no rewind ($moved)"
                )
            }
            return null
        }
        val choice = rewoundStart(positionMs, pausedMs, facts)
        if (pausedMs >= QUICK_PAUSE_MS) Diag.log("playback", "resumed $itemId: ${choice.why}")
        return choice.positionMs.takeIf { it != positionMs }
    }
}

internal class ResumeRewindListener(
    private val player: () -> Player?,
    private val rewind: ResumeRewind,
    private val rewindTo: (Player, Long) -> Unit = { p, ms -> p.seekTo(ms) },
    private val facts: () -> ResumeFacts,
) : Player.Listener {
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        changed(endOfItem = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        changed(endOfItem = false)
    }

    private fun changed(endOfItem: Boolean) {
        val player = player() ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        val moment = PlayerMoment(
            itemId = id,
            positionMs = player.currentPosition,
            playWhenReady = player.playWhenReady,
            ended = player.playbackState == Player.STATE_ENDED,
            focusLost = player.playbackSuppressionReason ==
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
        )
        val target = rewind.onChange(moment, endOfItem, facts().copy(live = player.isCurrentMediaItemLive))
        target?.let { rewindTo(player, it) }
    }
}

private const val QUICK_PAUSE_MS = 3_000L
private const val LONG_PAUSE_MS = 3_600_000L
private const val LEAST_MS = 2_000L
private const val MOST_MS = 20_000L
private const val MOVED_MS = 1_500L
