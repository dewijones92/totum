package com.dewijones92.totum.targets

import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.shownLengthMs
import com.dewijones92.totum.data.targets.toQueueEntry
import com.dewijones92.totum.domain.ItemShield
import com.dewijones92.totum.domain.ItemShields
import com.dewijones92.totum.playback.RemoteCommand
import com.dewijones92.totum.playback.RemoteFacts
import com.dewijones92.totum.playback.RemoteItem
import com.dewijones92.totum.ui.queue.chipLabel

internal fun TargetState.toRemoteFacts(
    colour: Long,
    statusLine: (deviceLabel: String, state: TargetState) -> String,
    shield: ItemShield = ItemShields,
): RemoteFacts? {
    val here = current ?: return null
    val label = target.chipLabel(power)
    return RemoteFacts(
        deviceId = target.id,
        deviceLabel = label,
        statusLine = statusLine(label, this),
        colour = colour,
        previous = entries.getOrNull(at - 1)?.toRemoteItem(at - 1, shield),
        current = here.toRemoteItem(at, shield) ?: return null,
        next = entries.getOrNull(at + 1)?.toRemoteItem(at + 1, shield),
        playing = playing,
        starting = starting,
        positionMs = positionMs,
        durationMs = shownLengthMs,
    )
}

private fun TargetEntry.toRemoteItem(index: Int, shield: ItemShield): RemoteItem? {
    val item = toQueueEntry()?.item?.item ?: return null
    val face = shield.face(item)
    return RemoteItem(index, item.id.value, face.title, item.author, face.artworkUrl?.value)
}

internal fun RemoteCommand.toControl(): TargetControl = when (this) {
    RemoteCommand.Play -> TargetControl(TargetAction.PLAY)
    RemoteCommand.Pause -> TargetControl(TargetAction.PAUSE)
    RemoteCommand.Next -> TargetControl(TargetAction.NEXT)
    RemoteCommand.Previous -> TargetControl(TargetAction.PREVIOUS)
    is RemoteCommand.SeekTo -> TargetControl(TargetAction.SEEK, positionMs = positionMs)
}
