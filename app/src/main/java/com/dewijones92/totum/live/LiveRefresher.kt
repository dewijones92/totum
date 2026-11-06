package com.dewijones92.totum.live

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeWatchUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.innertube.player.LiveCheck
import com.dewijones92.totum.video.LivePhase
import com.dewijones92.totum.video.LiveStates
import com.dewijones92.totum.video.LiveTag
import com.dewijones92.totum.video.liveTagOf
import com.dewijones92.totum.video.videoIdOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class LiveRefresher(
    private val scope: CoroutineScope,
    private val states: LiveStates,
    private val queued: () -> List<MediaItem>,
    private val check: suspend (HttpUrl) -> LiveCheck,
    private val onLearned: suspend (MediaItem) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val shown = ConcurrentHashMap<String, Int>()
    private var quietCycles = 0

    fun shown(videoId: String) {
        shown.merge(videoId, 1, Int::plus)
    }

    fun hidden(videoId: String) {
        shown.computeIfPresent(videoId) { _, count -> (count - 1).takeIf { it > 0 } }
    }

    fun start() = scope.launch {
        delay(FIRST_CYCLE_MS)
        while (true) {
            val answered = runCycle()
            quietCycles = if (answered) 0 else (quietCycles + 1).coerceAtMost(MAX_QUIET_CYCLES)
            val wait = CYCLE_MS shl quietCycles
            Diag.log(
                "live",
                "refresh cycle done (answered=$answered quietCycles=$quietCycles); next in ${wait / MS_MIN} min"
            )
            delay(wait)
        }
    }

    suspend fun runCycle(): Boolean {
        val at = clock()
        val queuedItems = queued().mapNotNull { item -> videoIdOf(item.id)?.let { it to item } }.toMap()
        val candidates = (queuedItems.keys + shown.keys)
            .distinct()
            .filter { id -> isWorthAsking(id, queuedItems[id], at) }
            .sortedBy { states.of(it)?.lastSeenMs ?: 0L }
            .take(MAX_PER_CYCLE)
        if (candidates.isEmpty()) {
            Diag.log(
                "live",
                "refresh cycle: nothing to ask (${shown.size} on screen, ${queuedItems.size} queued, none tagged " +
                    "live or starting soon and due a re-check)",
            )
            return true
        }
        Diag.log("live", "refresh cycle: asking about ${candidates.size} due: $candidates")
        var answered = 0
        for (id in candidates) {
            val url = youTubeWatchUrl(id) ?: continue
            if (learn(id, url, queuedItems[id])) answered++
        }
        return answered > 0
    }

    private fun isWorthAsking(videoId: String, queuedItem: MediaItem?, at: Long): Boolean {
        val state = states.of(videoId)
        val tag = liveTagOf(queuedItem?.contentKind ?: MediaContentKind.STANDARD, state)
        val tagged = tag is LiveTag.Live || tag is LiveTag.StartsSoon ||
            (videoId in shown.keys && state?.phase != LivePhase.ENDED)
        val due = state == null || at - state.lastSeenMs >= MIN_RECHECK_MS
        return tagged && due
    }

    private suspend fun learn(videoId: String, url: HttpUrl, queuedItem: MediaItem?): Boolean {
        val outcome = runCatching { check(url) }.getOrElse { failure ->
            Diag.warn("live", "$videoId: the refresh check threw", failure)
            LiveCheck.Unknown("threw ${failure.javaClass.simpleName}")
        }
        val was = states.of(videoId)?.phase
        when (outcome) {
            is LiveCheck.Live -> persist(videoId, queuedItem, MediaContentKind.LIVE, was, "still live (${outcome.via})")
            is LiveCheck.Ended -> persist(videoId, queuedItem, MediaContentKind.WAS_LIVE, was, "ended (${outcome.via})")
            is LiveCheck.StillUpcoming -> Diag.log(
                "live",
                "$videoId: refresh says still upcoming (was $was): ${outcome.upcoming.said} via ${outcome.via}",
            )
            is LiveCheck.Unknown -> Diag.log("live", "$videoId: refresh could not tell (was $was): ${outcome.why}")
        }
        return outcome !is LiveCheck.Unknown
    }

    private suspend fun persist(
        videoId: String,
        queuedItem: MediaItem?,
        kind: MediaContentKind,
        was: LivePhase?,
        why: String,
    ) {
        Diag.log("live", "$videoId: refresh learned $kind (registry was $was, queued=${queuedItem != null}): $why")
        if (queuedItem != null) onLearned(queuedItem.copy(contentKind = kind))
    }

    private companion object {
        const val MS_MIN = 60_000L
        const val FIRST_CYCLE_MS = 2 * MS_MIN
        const val CYCLE_MS = 5 * MS_MIN
        const val MIN_RECHECK_MS = 4 * MS_MIN
        const val MAX_PER_CYCLE = 6
        const val MAX_QUIET_CYCLES = 4
    }
}
