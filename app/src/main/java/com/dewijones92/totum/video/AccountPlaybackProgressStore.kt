package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.playback.Chosen
import com.dewijones92.totum.playback.PlaybackProgressStore

internal class AccountPlaybackProgressStore(
    private val local: PlaybackProgressStore,
    private val account: AccountResumePositions,
    private val byHand: PlayedByHand = NoPlayedByHand,
) : PlaybackProgressStore by local {
    override suspend fun resumePositionMs(itemId: MediaItemId): Long? = account.resumePositionMs(itemId)

    override suspend fun watchedFurtherElsewhereMs(itemId: MediaItemId, playingAtMs: Long, pausedMs: Long?): Long? =
        account.watchedFurtherElsewhereMs(itemId, playingAtMs, pausedMs)

    override suspend fun setPlayed(itemId: MediaItemId, played: Boolean) {
        if (!played) account.overriddenByReset(itemId)
        val wasPlayed = local.playState(itemId) is PlayState.Played
        local.setPlayed(itemId, played)
        when {
            !played -> byHand.unplayed(itemId)
            wasPlayed -> Diag.log(
                "yt-sync-item",
                "${itemId.value} marked played again: already Played here, so the account is not told",
            )
            else -> byHand.played(itemId)
        }
    }

    override suspend fun save(itemId: MediaItemId, positionMs: Long, durationMs: Long?, chosen: Chosen) {
        if (chosen == Chosen.BY_SEEKING) account.overriddenBySeek(itemId)
        local.save(itemId, positionMs, durationMs, chosen)
    }
}
