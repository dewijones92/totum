package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.playback.PlaybackProblem
import com.dewijones92.totum.queue.RefusedTap
import com.dewijones92.totum.targets.WayOut
import com.dewijones92.totum.targets.WayOutFacts
import com.dewijones92.totum.targets.wayOutOf

internal class RefusalWays(
    private val options: () -> List<PlayOnOption>,
    private val heldAtHome: (PlayableItem) -> Boolean,
    private val reach: (VideoCodecFamily) -> Int?,
    private val playOn: (PlayableItem, PlayOnOption) -> Unit,
    private val fetchCopy: (PlayableItem, Int) -> Unit,
) {
    fun reachOf(problem: PlaybackProblem.CannotDecode): Int? = problem.codecFamily?.let(reach)

    fun wayOut(tap: RefusedTap, isDownloaded: (MediaItemId) -> Boolean): WayOut? {
        val problem = tap.problem as? PlaybackProblem.CannotDecode ?: return null
        val item = tap.item ?: return null
        val way = wayOutOf(
            problem,
            item,
            WayOutFacts(
                heldAtHome = heldAtHome(item),
                downloaded = isDownloaded(item.item.id),
                options = options(),
                reach = reachOf(problem),
            ),
        )
        Diag.log(
            "ui",
            "way out of \"${tap.title}\" [${problem.format} ${problem.width}x${problem.height} " +
                "decoderStopped=${problem.decoderStopped} reach=${reachOf(problem)}p " +
                "atHome=${heldAtHome(item)} downloaded=${isDownloaded(item.item.id)}] -> ${way ?: "none offered"}",
        )
        return way
    }

    fun take(way: WayOut, item: PlayableItem) {
        when (way) {
            is WayOut.PlayOn -> playOn(item, way.option)
            is WayOut.FetchCopy -> fetchCopy(item, way.maxHeight)
        }
    }
}
