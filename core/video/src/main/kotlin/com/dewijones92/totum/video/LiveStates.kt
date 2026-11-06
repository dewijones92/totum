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

class LiveStates(
    private val store: LiveStatesStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val restored = restore()
    private val held = MutableStateFlow(restored)
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
        removed?.let {
            Diag.log("live", "$videoId no longer ${it.phase} in the registry: $why")
            save("$videoId cleared")
        }
    }

    fun diagnostics(): Map<String, String> {
        val at = now()
        val all = held.value.values
        val byPhase = LivePhase.entries.joinToString(" ") { phase ->
            "${phase.name.lowercase()}=${all.count { it.phase == phase }}"
        }
        return mapOf(
            "live.count" to if (all.isEmpty()) "none held" else byPhase,
            "live.restoredAtStart" to restored.size.toString(),
            "live.entries" to all.filter { it.phase != LivePhase.ENDED }
                .sortedBy { it.lastSeenMs }
                .take(DIAGNOSED)
                .joinToString("; ") { state ->
                    "${state.videoId} ${state.phase.name.lowercase()} startsAt=${state.startsAt ?: "not given"} " +
                        "seenMinAgo=${(at - state.lastSeenMs) / MS_PER_MINUTE}"
                }
                .ifEmpty { "none upcoming or live" },
        )
    }

    private fun restore(): Map<String, LiveState> {
        val at = now()
        val saved = store?.load().orEmpty()
        val kept = saved.filter { it.phase != LivePhase.ENDED && keeps(it, at) }.associateBy { it.videoId }
        if (store != null) {
            Diag.log(
                "live",
                "restored ${kept.size} of ${saved.size} saved live entr(ies): " +
                    kept.values.joinToString { "${it.videoId} ${it.phase} startsAt=${it.startsAt ?: "not given"}" }
                        .ifEmpty { "none" },
            )
        }
        return kept
    }

    private fun save(why: String) {
        val target = store ?: return
        val persistent = held.value.values.filter { it.phase != LivePhase.ENDED }
        target.save(persistent)
        Diag.log("live", "saved ${persistent.size} upcoming or live entr(ies) ($why)")
    }

    private fun put(videoId: String, phase: LivePhase, startsAt: Instant?, said: String, via: String) {
        val at = now()
        var was: LiveState? = null
        held.update { before ->
            was = before[videoId]
            val entry = LiveState(videoId, phase, startsAt, said, was?.firstSeenMs ?: at, at)
            prune(before, at) + (videoId to entry)
        }
        save("$videoId noted $phase via $via")
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

    private fun keeps(state: LiveState, at: Long): Boolean = when (state.phase) {
        LivePhase.ENDED -> true
        LivePhase.LIVE -> at - state.lastSeenMs < KEEP_MS
        LivePhase.UPCOMING -> state.startsAt?.let { at < it.toEpochMilli() + KEEP_AFTER_START_MS }
            ?: (at - state.lastSeenMs < KEEP_MS)
    }

    private fun prune(before: Map<String, LiveState>, at: Long): Map<String, LiveState> {
        val fresh = before.filterValues { keeps(it, at) }
        val ended = fresh.values.filter { it.phase == LivePhase.ENDED }
        if (ended.size <= MAX_ENDED) return fresh
        val drop = ended.sortedBy { it.lastSeenMs }.take(ended.size - MAX_ENDED).map { it.videoId }.toSet()
        return fresh - drop
    }

    private companion object {
        const val KEEP_MS = 12 * 60 * 60_000L
        const val KEEP_AFTER_START_MS = 48 * 60 * 60_000L
        const val MAX_ENDED = 500
        const val MS_PER_MINUTE = 60_000L
        const val DIAGNOSED = 20
    }
}
