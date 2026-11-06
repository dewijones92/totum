package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.kidlock.KidRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal fun contextOf(modes: List<ActiveMode>, next: NextAfter): PlaybackContext = PlaybackContext(
    repeatOne = ActiveMode.RepeatOne in modes,
    nextTitle = (next.line as? NextLine.Queued)?.title,
    explorePicksNext = next.line is NextLine.ExplorePicks,
    nextAfter = next.after?.value,
)

internal fun nextLineFor(c: AppContainer): Flow<NextAfter> {
    val queue = c.playbackQueue
    val queueFacts = combine(queue.state, queue.nowPlaying) { snapshot, playing -> snapshot to playing?.item }
    val modes = combine(effectiveRepeatFor(c), c.activeModes) { repeat, active ->
        repeat to (ActiveMode.Explore in active)
    }
    val stops = combine(
        c.appPreferences.settings.map { it.autoPlayNext }.distinctUntilChanged(),
        c.kidLock.locked,
        c.sleepTimer.state,
    ) { autoPlay, locked, _ -> KidRules.autoPlay(locked, autoPlay) }
    return combine(queueFacts, modes, stops) { (snapshot, playing), (repeat, explore), autoPlay ->
        val inputs = NextLineInputs(snapshot, playing, repeat, autoPlay, explore, c.sleepTimer.stopsAfterItem)
        NextAfter(playing?.id, nextLineOf(inputs, queue.passOver))
    }.distinctUntilChanged()
}

internal fun startPlaybackContext(c: AppContainer) {
    c.applicationScope.launch {
        combine(c.activeModes, nextLineFor(c), ::contextOf)
            .distinctUntilChanged()
            .collect { context ->
                Diag.log("playback", "dewidebug playback context for the session: $context")
                c.playbackController.setPlaybackContext(context)
            }
    }
}
