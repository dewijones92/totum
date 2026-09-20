package com.dewijones92.totum.playback.fake

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.playback.Chosen
import com.dewijones92.totum.playback.PlaybackProgressStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [PlaybackProgressStore] for tests and previews.
 *
 * Records exactly what it is told, with none of the Room store's rules (ignore trivial
 * positions, treat near-the-end as finished). Anything asserting those rules belongs in
 * the instrumented test of the real store; anything asserting a *caller's* behaviour
 * wants this, where what goes in is what comes out.
 */
public class InMemoryPlaybackProgressStore : PlaybackProgressStore {

    private val states = MutableStateFlow<Map<MediaItemId, PlayState>>(emptyMap())

    override suspend fun resumePositionMs(itemId: MediaItemId): Long? =
        (playState(itemId) as? PlayState.InProgress)?.positionMs

    override suspend fun playState(itemId: MediaItemId): PlayState = states.value[itemId] ?: PlayState.Unplayed

    override suspend fun save(itemId: MediaItemId, positionMs: Long, durationMs: Long?, chosen: Chosen) {
        // [Chosen.AS_A_RECORD] is a PORT rule ("completion is left exactly as it is"), not one of
        // the Room store's own, so a double that ignored it could not catch a caller passing it
        // wrongly. The floor and the near-the-end rule stay absent, which is the point of this one.
        if (chosen == Chosen.AS_A_RECORD && states.value[itemId] is PlayState.Played) return
        states.update { it + (itemId to PlayState.InProgress(positionMs, durationMs)) }
    }

    override fun observeStates(): Flow<Map<MediaItemId, PlayState>> = states

    override suspend fun setPlayed(itemId: MediaItemId, played: Boolean) {
        states.update { if (played) it + (itemId to PlayState.Played) else it - itemId }
    }
}
