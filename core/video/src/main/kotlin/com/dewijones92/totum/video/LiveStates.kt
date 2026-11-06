package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.innertube.player.UpcomingLive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

enum class LivePhase { UPCOMING, LIVE, ENDED }

data class LiveState(
    val videoId: String,
    val phase: LivePhase,
    val startsAt: Instant?,
    val said: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

fun videoIdOf(id: MediaItemId): String? = HttpUrl.parse(id.value)?.youTubeVideoId()

class LiveStates(private val now: () -> Long = System::currentTimeMillis) {
    private val held = MutableStateFlow<Map<String, LiveState>>(emptyMap())
    val states: StateFlow<Map<String, LiveState>> = held.asStateFlow()

    fun of(videoId: String): LiveState? = held.value[videoId]

    fun noteUpcoming(videoId: String, upcoming: UpcomingLive, via: String) {
        put(videoId, LivePhase.UPCOMING, upcoming.startsAt, upcoming.said, via)
    }

    fun noteLive(videoId: String, via: String) {
        put(videoId, LivePhase.LIVE, null, "", via)
    }

    fun noteEnded(videoId: String, via: String) {
        put(videoId, LivePhase.ENDED, null, "", via)
    }

    fun observed(videoId: String, kind: MediaContentKind, via: String) {
        when (kind) {
            MediaContentKind.LIVE -> noteLive(videoId, via)
            MediaContentKind.WAS_LIVE -> noteEnded(videoId, via)
            else -> if (of(videoId)?.phase == LivePhase.UPCOMING) cleared(videoId, "it resolved as a video ($via)")
        }
    }

    fun cleared(videoId: String, why: String) {
        var removed: LiveState? = null
        held.update { before ->
            removed = before[videoId]
            if (removed != null) before - videoId else before
        }
        removed?.let { Diag.log("live", "$videoId no longer ${it.phase} in the registry: $why") }
    }

    private fun put(videoId: String, phase: LivePhase, startsAt: Instant?, said: String, via: String) {
        val at = now()
        var was: LiveState? = null
        held.update { before ->
            was = before[videoId]
            val entry = LiveState(videoId, phase, startsAt, said, was?.firstSeenMs ?: at, at)
            prune(before, at) + (videoId to entry)
        }
        val earlier = was
        val change = when {
            earlier == null -> "new"
            earlier.phase != phase -> "was ${earlier.phase}"
            earlier.startsAt != startsAt -> "start time changed from ${earlier.startsAt}"
            else -> "unchanged"
        }
        Diag.log(
            "live",
            "$videoId is $phase ($change) via $via: startsAt=${startsAt ?: "not given"}" +
                (if (said.isBlank()) "" else " said \"$said\"") + "; ${held.value.size} held",
        )
    }

    private fun prune(before: Map<String, LiveState>, at: Long): Map<String, LiveState> {
        val fresh = before.filterValues { it.phase == LivePhase.ENDED || at - it.lastSeenMs < KEEP_MS }
        val ended = fresh.values.filter { it.phase == LivePhase.ENDED }
        if (ended.size <= MAX_ENDED) return fresh
        val drop = ended.sortedBy { it.lastSeenMs }.take(ended.size - MAX_ENDED).map { it.videoId }.toSet()
        return fresh - drop
    }

    private companion object {
        const val KEEP_MS = 12 * 60 * 60_000L
        const val MAX_ENDED = 500
    }
}
