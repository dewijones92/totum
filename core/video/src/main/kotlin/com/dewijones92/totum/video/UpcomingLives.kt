package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.innertube.player.UpcomingLive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

data class StartsSoon(
    val videoId: String,
    val startsAt: Instant?,
    val said: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

fun videoIdOf(id: MediaItemId): String? = HttpUrl.parse(id.value)?.youTubeVideoId()

class UpcomingLives(private val now: () -> Long = System::currentTimeMillis) {
    private val held = MutableStateFlow<Map<String, StartsSoon>>(emptyMap())
    val waiting: StateFlow<Map<String, StartsSoon>> = held.asStateFlow()

    fun of(videoId: String): StartsSoon? = held.value[videoId]

    fun note(videoId: String, upcoming: UpcomingLive, via: String) {
        val at = now()
        var change = "refreshed"
        held.update { before ->
            val earlier = before[videoId]
            change = when {
                earlier == null -> "new"
                earlier.startsAt != upcoming.startsAt -> "start time changed from ${earlier.startsAt}"
                else -> "refreshed"
            }
            val kept = before.filterValues { at - it.lastSeenMs < KEEP_MS }
            val entry = StartsSoon(videoId, upcoming.startsAt, upcoming.said, earlier?.firstSeenMs ?: at, at)
            kept + (videoId to entry)
        }
        Diag.log(
            "live",
            "$videoId has not started ($change) via $via: \"${upcoming.said}\" " +
                "startsAt=${upcoming.startsAt ?: "not given"}; ${held.value.size} upcoming held",
        )
    }

    fun cleared(videoId: String, why: String) {
        var removed = false
        held.update { before ->
            removed = videoId in before
            if (removed) before - videoId else before
        }
        if (removed) Diag.log("live", "$videoId no longer shown as starting soon: $why")
    }

    private companion object {
        const val KEEP_MS = 12 * 60 * 60_000L
    }
}
