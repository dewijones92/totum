package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.toPlayableOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExploreMode(
    initiallyOn: Boolean,
    private val related: suspend (MediaItem) -> List<MediaItem>?,
    private val peek: suspend (PlayableItem) -> Boolean,
    private val nowPlaying: () -> PlayableItem?,
    private val onTurnedOn: () -> Unit = {},
    private val cap: () -> Int = { 0 },
) {
    private val state = MutableStateFlow(initiallyOn)
    val on: StateFlow<Boolean> = state.asStateFlow()

    private val played = ArrayDeque<MediaItemId>()
    private var inARow = 0
    private var lastExplored: MediaItemId? = null

    init {
        Diag.log("explore", "explore starts ${if (initiallyOn) "on (the Settings default)" else "off"}")
    }

    fun set(enabled: Boolean, from: String) {
        if (state.value == enabled) return
        state.value = enabled
        Diag.log("explore", "explore ${if (enabled) "on" else "off"} from $from")
        if (enabled) {
            played.clear()
            onTurnedOn()
        }
    }

    suspend fun afterEnded(endedId: MediaItemId): Boolean {
        if (!state.value) return false
        val ended = nowPlaying()?.item?.takeIf { it.id == endedId } ?: return false.also {
            Diag.log("explore", "explore on, but ${endedId.value} is no longer what is playing; the queue decides")
        }
        remember(ended.id)
        val limit = cap()
        if (capReached(ended.id, limit)) return false
        return follow(ended, limit)
    }

    private suspend fun follow(ended: MediaItem, limit: Int): Boolean {
        val candidates = related(ended) ?: return false.also {
            Diag.log("explore", "nothing related to ${ended.id.value}; the queue carries on from its place")
        }
        val fresh = candidates.filter { it.id !in played && it.explorable }
        val next = fresh.firstNotNullOfOrNull { it.toPlayableOrNull() } ?: return false.also {
            Diag.log(
                "explore",
                "none of ${candidates.size} related to ${ended.id.value} can follow " +
                    "(${candidates.size - fresh.size} played in this run, live, Shorts or members-only; " +
                    "${fresh.size} without a playable link); the queue carries on from its place",
            )
        }
        if (overtaken(ended.id)) return false
        remember(next.item.id)
        val started = peek(next)
        if (started) {
            inARow++
            lastExplored = next.item.id
        }
        Diag.log(
            "explore",
            "after ${ended.id.value}: \"${next.item.title.take(TITLE_CHARS)}\" " +
                "${if (started) "playing" else "did not start"} (${candidates.size} related, " +
                "${played.size} played in this run, $inARow in a row${if (limit > 0) " of at most $limit" else ""})",
        )
        return started
    }

    suspend fun next(): Boolean? {
        if (!state.value) return null
        val playing = nowPlaying()?.item?.id ?: return null
        Diag.log("explore", "Next pressed while exploring ${playing.value}")
        return if (afterEnded(playing)) true else null
    }

    private fun overtaken(endedId: MediaItemId): Boolean {
        if (nowPlaying()?.item?.id == endedId) return false
        Diag.log("explore", "related to ${endedId.value} arrived after something else started; not interrupting it")
        return true
    }

    private fun capReached(endedId: MediaItemId, limit: Int): Boolean {
        if (lastExplored != endedId) inARow = 0
        if (limit !in 1..inARow) return false
        Diag.log("explore", "explored $inARow in a row, the cap is $limit: the queue gets its turn")
        inARow = 0
        lastExplored = null
        return true
    }

    private fun remember(id: MediaItemId) {
        played.remove(id)
        played.addLast(id)
        while (played.size > KEEP) played.removeFirst()
    }

    private companion object {
        const val KEEP = 200
        const val TITLE_CHARS = 60
    }
}

private val MediaItem.explorable: Boolean
    get() = !membersOnly && contentKind != MediaContentKind.LIVE && contentKind != MediaContentKind.SHORT
