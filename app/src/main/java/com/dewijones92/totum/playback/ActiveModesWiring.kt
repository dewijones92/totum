package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.WorkAppsStatus
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.kidlock.KidRules
import com.dewijones92.totum.mobiledata.holdingBack
import com.dewijones92.totum.settings.RepeatMode
import com.dewijones92.totum.workapps.WorkAppsQuiet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private const val QUIET_RECHECK_MAX_MS = 5 * 60_000L
private const val QUIET_RECHECK_MIN_MS = 1_000L

internal fun effectiveRepeatFor(c: AppContainer): Flow<RepeatMode> = combine(
    c.kidLock.locked,
    c.playbackQueue.state.map { it.current?.group?.id }.distinctUntilChanged(),
    c.appPreferences.settings.map { it.repeatMode }.distinctUntilChanged(),
) { locked, group, chosen -> KidRules.repeat(locked, group, chosen) }

internal fun activeModesFor(c: AppContainer): Flow<List<ActiveMode>> {
    val first = combine(
        effectiveRepeatFor(c),
        c.exploreMode.on,
        c.kidLock.locked,
        c.playbackController.state.map { it?.speed }.distinctUntilChanged(),
        quietHoursNow(c.workApps),
    ) { chosen, explore, kidLocked, speed, quiet ->
        ModeInputs(chosen, explore, kidLocked, speed, quiet)
    }
    val sleeping = c.sleepTimer.state.map { it != SleepTimerState.Off }.distinctUntilChanged()
    val limited = combine(
        c.mobileData.state,
        c.appPreferences.settings.map { it.dataLimitMb }.distinctUntilChanged(),
    ) { used, limitMb -> used.holdingBack(limitMb) }.distinctUntilChanged()
    return combine(first, sleeping, limited) { inputs, asleep, overLimit ->
        inputs.copy(sleepTimer = asleep, dataLimit = overLimit)
    }
        .map(::activeModesOf)
        .distinctUntilChanged()
}

internal fun startActiveModes(c: AppContainer) {
    c.applicationScope.launch {
        var before: List<ActiveMode>? = null
        c.activeModes.collect { modes ->
            Diag.log(
                "modes",
                "dewidebug active modes ${before?.describe() ?: "(first reading)"} -> ${modes.describe()}"
            )
            before = modes
        }
    }
}

private fun quietHoursNow(workApps: WorkAppsQuiet): Flow<Boolean> =
    workApps.settings.flatMapLatest {
        flow {
            while (true) {
                val status = workApps.status()
                emit(status is WorkAppsStatus.Quiet)
                val untilChange = status?.refreshAt?.let { Duration.between(Instant.now(), it).toMillis() }
                delay((untilChange ?: QUIET_RECHECK_MAX_MS).coerceIn(QUIET_RECHECK_MIN_MS, QUIET_RECHECK_MAX_MS))
            }
        }
    }.distinctUntilChanged()
