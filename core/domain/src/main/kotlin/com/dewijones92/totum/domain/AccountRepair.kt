package com.dewijones92.totum.domain

public sealed interface AccountRepair {
    public val accountFigureMs: Long
    public val actedOnMs: Long

    public data class Finish(val durationMs: Long, override val accountFigureMs: Long) : AccountRepair {
        override val actedOnMs: Long get() = durationMs
    }

    public data class Position(
        val positionMs: Long,
        val durationMs: Long,
        override val accountFigureMs: Long,
    ) : AccountRepair {
        override val actedOnMs: Long get() = positionMs
    }
}

public fun accountRepairFor(
    local: PlayState?,
    remotePositionMs: Long,
    remoteDurationMs: Long,
    reconciledMs: Long?,
): AccountRepair? {
    if (remoteDurationMs <= 0 || isWatchedToTheEnd(remotePositionMs, remoteDurationMs)) return null
    if (reconciledMs != null && reconciledMs != remotePositionMs) return null
    return when (local) {
        PlayState.Played -> AccountRepair.Finish(remoteDurationMs, remotePositionMs)
        is PlayState.InProgress -> {
            if (local.positionMs - remotePositionMs > accountGranularityMs(remoteDurationMs)) {
                AccountRepair.Position(local.positionMs, remoteDurationMs, remotePositionMs)
            } else {
                null
            }
        }
        PlayState.Unplayed, null -> null
    }
}
