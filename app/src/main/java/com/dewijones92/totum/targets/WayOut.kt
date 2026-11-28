package com.dewijones92.totum.targets

import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.isJellyfin
import com.dewijones92.totum.domain.isTorrent
import com.dewijones92.totum.playback.PlaybackProblem
import com.dewijones92.totum.playback.TrackKind

internal sealed interface WayOut {
    data class PlayOn(val option: PlayOnOption) : WayOut

    data class FetchCopy(val maxHeight: Int) : WayOut
}

internal data class WayOutFacts(
    val heldAtHome: Boolean,
    val downloaded: Boolean,
    val options: List<PlayOnOption>,
    val reach: Int?,
)

internal fun wayOutOf(problem: PlaybackProblem, item: PlayableItem?, facts: WayOutFacts): WayOut? {
    if (problem !is PlaybackProblem.CannotDecode || item == null) return null
    val reach = facts.reach
    val atHome = facts.heldAtHome || item.item.isTorrent || item.item.isJellyfin
    if (atHome) {
        val ready = facts.options.filter { !it.isPhone && it.plays.refusal(item) == null }
        val pick = ready.firstOrNull { it.power != TargetPower.ASLEEP } ?: ready.firstOrNull()
        if (pick != null) return WayOut.PlayOn(pick)
    }
    val tooBig = problem.height?.let { height -> reach != null && height > reach } == true
    if (problem.track == TrackKind.VIDEO && facts.downloaded && tooBig) {
        return WayOut.FetchCopy(checkNotNull(reach))
    }
    return null
}
