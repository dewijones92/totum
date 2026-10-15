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

@Suppress("LongParameterList")
class ExploreMode(
    initiallyOn: Boolean,
    private val related: suspend (MediaItem) -> List<MediaItem>?,
    private val peek: suspend (PlayableItem) -> Boolean,
    private val nowPlaying: () -> PlayableItem?,
    private val queueCurrent: () -> MediaItemId?,
    private val queueEntries: () -> List<MediaItemId>,
    private val playQueueAt: suspend (Int) -> Boolean,
    private val onTurnedOn: () -> Unit = {},
    resumeAtStart: MediaItemId? = null,
    private val saveResume: (MediaItemId?) -> Unit = {},
) {
    private val state = MutableStateFlow(initiallyOn)
    val on: StateFlow<Boolean> = state.asStateFlow()

    private val place = MutableStateFlow(resumeAtStart)
    val queuePlace: StateFlow<MediaItemId?> = place.asStateFlow()

    var resumeAfter: MediaItemId?
        get() = place.value
        private set(value) {
            if (place.value == value) return
            place.value = value
            saveResume(value)
        }

    private val played = ArrayDeque<MediaItemId>()

    fun set(enabled: Boolean, from: String) {
        if (state.value == enabled) return
        state.value = enabled
        Diag.log(
            "explore",
            "explore ${if (enabled) "on" else "off"} from $from" +
                (resumeAfter?.let { "; the queue resumes after ${it.value}" } ?: ""),
        )
        if (enabled) {
            played.clear()
            onTurnedOn()
        }
    }

    fun toggle(from: String) = set(!state.value, from)

    suspend fun afterEnded(endedId: MediaItemId): Boolean {
        if (!state.value) return false
        val ended = nowPlaying()?.item?.takeIf { it.id == endedId } ?: return false.also {
            Diag.log("explore", "explore on, but ${endedId.value} is no longer what is playing; the queue decides")
        }
        queueCurrent()?.let { inQueue ->
            if (resumeAfter != inQueue) Diag.log("explore", "the queue's place is now ${inQueue.value}")
            resumeAfter = inQueue
        }
        remember(ended.id)
        val candidates = related(ended) ?: return false.also {
            Diag.warn("explore", "no related videos for ${ended.id.value}; the queue carries on")
        }
        val pick = candidates.firstOrNull { it.id !in played && it.explorable }
        val next = pick?.toPlayableOrNull() ?: return false.also {
            Diag.log(
                "explore",
                "all ${candidates.size} related to ${ended.id.value} were played in this run; the queue carries on",
            )
        }
        val started = peek(next)
        Diag.log(
            "explore",
            "after ${ended.id.value}: \"${next.item.title.take(TITLE_CHARS)}\" " +
                "${if (started) "playing" else "did not start"} (${candidates.size} related, " +
                "${played.size} played in this run, queue waits after ${resumeAfter?.value})",
        )
        if (started) remember(next.item.id)
        return started
    }

    suspend fun next(): Boolean? {
        if (!state.value) return resumeQueue()
        val playing = nowPlaying()?.item?.id ?: return null
        Diag.log("explore", "Next pressed while exploring ${playing.value}")
        return if (afterEnded(playing)) true else null
    }

    suspend fun resumeQueue(): Boolean? {
        if (state.value) return null
        val place = resumeAfter ?: return null
        resumeAfter = null
        val index = queueEntries().indexOf(place)
        if (index < 0) {
            Diag.log("explore", "the queue's place ${place.value} has left the queue; it carries on as usual")
            return null
        }
        Diag.log("explore", "explore off: the queue resumes at ${index + 1}, after ${place.value}")
        return playQueueAt(index + 1)
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
