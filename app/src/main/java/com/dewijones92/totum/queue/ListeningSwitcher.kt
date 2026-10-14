package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.Listening
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SwitchTarget
import com.dewijones92.totum.domain.listening
import com.dewijones92.totum.domain.switchTarget
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.RecentActions
import com.dewijones92.totum.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ListeningSwitcher(
    private val queue: PlaybackQueue,
    private val controller: PlaybackController,
    private val recent: RecentActions,
    private val playPin: suspend (Pin) -> Unit,
    private val scope: CoroutineScope,
) {
    private val lastOf = mutableMapOf<Listening, MediaItemId>()
    private val mutableTarget = MutableStateFlow<Listening?>(null)
    val target: StateFlow<Listening?> = mutableTarget.asStateFlow()

    fun start() {
        scope.launch {
            queue.state.map { it.current?.item?.item }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { item ->
                item?.let { lastOf[it.listening] = it.id }
                val to = item?.listening?.other
                if (to != mutableTarget.value) {
                    Diag.log(
                        TAG,
                        "switch now goes to ${to ?: "nothing"} (playing ${item?.title})"
                    )
                }
                mutableTarget.value = to
                controller.showSwitchButton(to)
            }
        }
        scope.launch { controller.switchRequests.collect { from -> toggle(from) } }
    }

    fun tap(from: String) {
        scope.launch { toggle(from) }
    }

    suspend fun toggle(from: String) {
        val snapshot = queue.state.value
        val playing = snapshot.current?.item?.item
        val target = switchTarget(snapshot.entries.map { it.item.item }, snapshot.currentIndex, lastOf.toMap())
        Diag.log(
            TAG,
            "switch from $from: playing=${playing?.title} (${playing?.listening}) " +
                "remembered=${lastOf.mapValues { it.value.value }} -> $target",
        )
        when (target) {
            is SwitchTarget.JumpTo -> queue.jumpTo(target.index)
            is SwitchTarget.PlayRecentMusic -> {
                val pin = recent.recent.value.firstOrNull { it.isMusic }
                    ?: return Diag.log(TAG, "no music in the queue and none in Recently: nothing to switch to")
                Diag.log(TAG, "playing the newest music in Recently: ${pin.key} \"${pin.title}\"")
                playPin(pin)
            }
            is SwitchTarget.Nowhere -> Unit
        }
    }

    private companion object {
        const val TAG = "switch"
    }
}

val Pin.isMusic: Boolean
    get() = when (this) {
        is Pin.Album, is Pin.Artist, is Pin.Radio, is Pin.ArtistSongs -> true
        is Pin.Playlist -> music
        is Pin.Item -> item.contentKind == MediaContentKind.MUSIC.name
        is Pin.Show, is Pin.ShuffleAllOf -> false
    }
