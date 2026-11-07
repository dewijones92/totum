package com.dewijones92.totum.playback

import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.Listening
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.listening
import com.dewijones92.totum.queue.admits
import com.dewijones92.totum.queue.groupRestartIndex
import com.dewijones92.totum.queue.nextAfterThePlace
import com.dewijones92.totum.settings.RepeatMode

internal sealed interface NextLine {
    val diagName: String

    data class Queued(val title: String) : NextLine {
        override val diagName: String get() = "queued:$title"
    }

    data object ExplorePicks : NextLine {
        override val diagName: String get() = "explore-picks"
    }

    data class Unknown(val why: String) : NextLine {
        override val diagName: String get() = "none:$why"
    }
}

internal data class NextAfter(val after: MediaItemId?, val line: NextLine)

internal data class NextLineInputs(
    val snapshot: QueueSnapshot,
    val playing: MediaItem?,
    val repeat: RepeatMode,
    val autoPlay: Boolean,
    val explore: Boolean,
    val sleepStopsAfter: MediaItemId?,
)

internal fun nextLineOf(
    inputs: NextLineInputs,
    passOver: (PlayableItem) -> String?,
    titleOf: (MediaItem) -> String = MediaItem::title,
): NextLine {
    val playing = inputs.playing ?: return NextLine.Unknown("nothing is playing")
    val keepTo = playing.listening
    return when {
        inputs.sleepStopsAfter == playing.id -> NextLine.Unknown("the sleep timer stops after it")
        inputs.repeat == RepeatMode.ONE -> NextLine.Unknown("repeat one replays it")
        !inputs.autoPlay -> NextLine.Unknown("auto-play next is off")
        inputs.repeat.repeatsGroup -> repeatGroupNext(inputs, playing, passOver, titleOf)
        inputs.explore -> NextLine.ExplorePicks
        else -> nextAfterThePlace(inputs.snapshot, playing.id, keepTo, passOver)
            ?.let { NextLine.Queued(titleOf(it.item)) }
            ?: afterTheQueue(inputs, keepTo, titleOf)
    }
}

private fun repeatGroupNext(
    inputs: NextLineInputs,
    playing: MediaItem,
    passOver: (PlayableItem) -> String?,
    titleOf: (MediaItem) -> String,
): NextLine {
    val restart = groupRestartIndex(inputs.snapshot, playing.id)
        ?: return nextAfterThePlace(inputs.snapshot, playing.id, playing.listening, passOver)
            ?.let { NextLine.Queued(titleOf(it.item)) }
            ?: NextLine.Unknown("nothing is queued after it")
    if (inputs.repeat.reshuffles) return NextLine.Unknown("the group reshuffles for its next round")
    return NextLine.Queued(titleOf(inputs.snapshot.entries[restart].item.item))
}

private fun afterTheQueue(inputs: NextLineInputs, keepTo: Listening, titleOf: (MediaItem) -> String): NextLine {
    if (!inputs.repeat.repeatsQueue) return NextLine.Unknown("nothing is queued after it")
    if (inputs.repeat.reshuffles) return NextLine.Unknown("the queue reshuffles for its next round")
    val first = inputs.snapshot.entries.firstOrNull { keepTo.admits(it.item) }
        ?: return NextLine.Unknown("nothing of its kind to repeat")
    return NextLine.Queued(titleOf(first.item.item))
}
