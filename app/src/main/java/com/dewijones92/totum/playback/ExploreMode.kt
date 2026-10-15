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
) {
    private val state = MutableStateFlow(initiallyOn)
    val on: StateFlow<Boolean> = state.asStateFlow()

    private val played = ArrayDeque<MediaItemId>()

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
        if (nowPlaying()?.item?.id != endedId) {
            Diag.log(
                "explore",
                "related to ${ended.id.value} arrived after something else started; not interrupting it"
            )
            return false
        }
        remember(next.item.id)
        val started = peek(next)
        Diag.log(
            "explore",
            "after ${ended.id.value}: \"${next.item.title.take(TITLE_CHARS)}\" " +
                "${if (started) "playing" else "did not start"} (${candidates.size} related, " +
                "${played.size} played in this run)",
        )
        return started
    }

    suspend fun next(): Boolean? {
        if (!state.value) return null
        val playing = nowPlaying()?.item?.id ?: return null
        Diag.log("explore", "Next pressed while exploring ${playing.value}")
        return if (afterEnded(playing)) true else null
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
