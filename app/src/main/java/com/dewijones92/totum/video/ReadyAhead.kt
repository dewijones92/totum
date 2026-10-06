package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

class ReadyAhead(
    private val metered: () -> Boolean,
    private val prefetch: suspend (HttpUrl, SourceId) -> VideoResolver.Resolved?,
    private val scope: CoroutineScope,
    private val maxWaiting: Int = MAX_WAITING,
) {
    private val waiting = ArrayDeque<Pair<PlayableItem, String>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            for (ignored in wake) {
                while (true) {
                    val (item, why) = synchronized(waiting) { waiting.removeFirstOrNull() } ?: break
                    lookUp(item, why)
                }
            }
        }
    }

    fun ready(item: PlayableItem?, why: String) {
        if (item == null || item.pillar != MediaKind.VIDEO || item.fetchUrl == null) return
        val id = item.item.id.value
        if (metered()) {
            Diag.log("resolve", "not looking up $id ahead ($why): the network is metered")
            return
        }
        synchronized(waiting) {
            waiting.removeAll { it.first.item.id == item.item.id }
            waiting.addFirst(item to why)
            while (waiting.size > maxWaiting) {
                val dropped = waiting.removeLast()
                Diag.log(
                    "resolve",
                    "dropped ${dropped.first.item.id.value} from the look-ahead (${dropped.second}): newer came first",
                )
            }
        }
        wake.trySend(Unit)
    }

    fun followQueue(
        nowPlaying: Flow<PlayableItem?>,
        nextUp: () -> PlayableItem?,
        hasLocalCopy: suspend (MediaItemId) -> Boolean,
    ) {
        scope.launch {
            nowPlaying.filterNotNull().distinctUntilChangedBy { it.item.id }.collect {
                val next = nextUp() ?: return@collect
                if (hasLocalCopy(next.item.id)) {
                    Diag.log("resolve", "not looking up next ${next.item.id.value} ahead: it plays from the disk")
                } else {
                    ready(next, "next in queue")
                }
            }
        }
    }

    private suspend fun lookUp(item: PlayableItem, why: String) {
        val url = item.fetchUrl ?: return
        val id = item.item.id.value
        if (metered()) {
            Diag.log("resolve", "not looking up $id ahead ($why): the network became metered")
            return
        }
        val started = TimeSource.Monotonic.markNow()
        Diag.log("resolve", "looking up $id ahead ($why)")
        val resolved = prefetch(url, item.item.sourceId)
        val ms = started.elapsedNow().inWholeMilliseconds
        Diag.log(
            "resolve",
            "$id ${if (resolved != null) "ready ahead" else "could not be looked up ahead"} in ${ms}ms ($why)"
        )
    }

    private companion object {
        const val MAX_WAITING = 4
    }
}
