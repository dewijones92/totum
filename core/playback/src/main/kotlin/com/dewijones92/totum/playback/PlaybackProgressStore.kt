package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Remembers how far into each item playback reached, so anything — a podcast
 * episode or a video — resumes where it was left, and every list can show whether
 * an item is unplayed, part-way or finished. One seam for both pillars; the
 * controller saves as it plays and restores on the next play.
 */
public interface PlaybackProgressStore {

    /** Position to resume [itemId] at, or null to start from the beginning. */
    public suspend fun resumePositionMs(itemId: MediaItemId): Long?

    /**
     * Everything this device knows about [itemId], in one read.
     *
     * [resumePositionMs] answers null for a FINISHED item and for one never played, and callers
     * that need to tell those apart were going somewhere else for the difference — a cached map
     * of every item's state, updated asynchronously, consulted one line before this row is read
     * anyway. Two sources for one fact is the mistake this codebase keeps paying for, so the
     * authoritative read hands back the whole answer.
     */
    public suspend fun playState(itemId: MediaItemId): PlayState

    /**
     * Records playback progress for [itemId]. Implementations may treat a
     * position near [durationMs] as finished — which marks the item played, and
     * still restarts it from the beginning next time — and may ignore trivially
     * small positions.
     *
     * [chosen] says where the position came from, which decides both of those. See [Chosen].
     */
    public suspend fun save(
        itemId: MediaItemId,
        positionMs: Long,
        durationMs: Long?,
        chosen: Chosen = Chosen.NO,
    )

    /**
     * Play state of every item that has one. Items absent from the map are
     * [PlayState.Unplayed], so a list reads this once rather than querying per row.
     */
    public fun observeStates(): Flow<Map<MediaItemId, PlayState>>

    /**
     * Marks [itemId] played or unplayed by hand — AntennaPod's most-used row action.
     * Marking unplayed clears any resume point, so the item starts clean.
     */
    public suspend fun setPlayed(itemId: MediaItemId, played: Boolean)
}

/** Default store that remembers nothing — playback still works, resume just no-ops. */
public object NoOpPlaybackProgressStore : PlaybackProgressStore {
    override suspend fun resumePositionMs(itemId: MediaItemId): Long? = null
    override suspend fun playState(itemId: MediaItemId): PlayState = PlayState.Unplayed
    override suspend fun save(
        itemId: MediaItemId,
        positionMs: Long,
        durationMs: Long?,
        chosen: Chosen,
    ): Unit = Unit
    override fun observeStates(): Flow<Map<MediaItemId, PlayState>> = flowOf(emptyMap())
    override suspend fun setPlayed(itemId: MediaItemId, played: Boolean): Unit = Unit
}

/**
 * Where a saved position came from — which decides whether the small-position floor applies, and
 * what it may say about the item being finished.
 *
 * One flag was not enough, and the missing distinction was a real defect both ways. Report 0.1.496
 * needed a rewind to escape the floor; making that one boolean then either un-played everything the
 * account was merely behind on, or (preserving completion instead) wrote a position that
 * `resumePositionMs` refuses to return, so a scrub on a replay was silently forgotten.
 */
public enum class Chosen {
    /**
     * Nobody chose it; playback reached here. The floor applies, so a quick tap leaves no resume
     * point — except where the position moves an existing one BACK, which is a rewind.
     */
    NO,

    /**
     * The person moved to here — the scrubber, a skip button, a chapter, a SponsorBlock skip on
     * their behalf — and is watching from it. No floor, and a finished item is in progress again,
     * exactly as the next ordinary save would have made it.
     */
    BY_SEEKING,

    /**
     * A position being REINSTATED rather than reached: adopted from the account, or restored from
     * a backup. No floor, and completion is left exactly as it is — YouTube being 30% through
     * something you finished here is not a reason to un-finish it, and its 99% is not a reason to
     * mark something played before a frame has run.
     */
    AS_A_RECORD,
}
