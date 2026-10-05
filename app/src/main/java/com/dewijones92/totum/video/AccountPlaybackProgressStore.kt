package com.dewijones92.totum.video

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.Chosen
import com.dewijones92.totum.playback.PlaybackProgressStore

internal class AccountPlaybackProgressStore(
    private val local: PlaybackProgressStore,
    private val account: AccountResumePositions,
) : PlaybackProgressStore by local {
    override suspend fun resumePositionMs(itemId: MediaItemId): Long? = account.resumePositionMs(itemId)

    override suspend fun save(itemId: MediaItemId, positionMs: Long, durationMs: Long?, chosen: Chosen) {
        if (chosen == Chosen.BY_SEEKING) account.overriddenBySeek(itemId)
        local.save(itemId, positionMs, durationMs, chosen)
    }
}
