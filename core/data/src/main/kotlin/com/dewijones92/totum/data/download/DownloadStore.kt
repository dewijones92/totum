package com.dewijones92.totum.data.download

import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SkipSegment
import kotlinx.coroutines.flow.Flow

/** Persistence port for download records; implemented by :core:database (Room). */
public interface DownloadStore {
    public suspend fun learnFacts(resolved: MediaItem)
    public fun observeAll(): Flow<Map<MediaItemId, DownloadState>>

    /**
     * Every finished download with the item it belongs to — what an offline library
     * lists. A record stores the item itself, so this needs no pillar's catalogue to
     * join against.
     */
    public fun observeDownloaded(): Flow<List<DownloadedMedia>>

    /** Records [state] against [item], keeping the item's own columns current. */
    /**
     * [audioOnly] is what was ASKED FOR, which only the request knows.
     *
     * A finished download carries the flag in its own state, but a row that is still going or has
     * failed does not — and that is exactly the row a retry reads. Without it, retrying an
     * audio-only download would quietly fetch the whole video instead.
     */
    public suspend fun put(item: PlayableItem, state: DownloadState, audioOnly: Boolean)
    public suspend fun get(id: MediaItemId): DownloadState

    /**
     * What was originally asked for, whatever state the row is in now — or null if there is no row.
     *
     * Distinct from [observeDownloaded], which only knows about FINISHED downloads. A retry starts
     * from a failed one, and a cancel needs the item to report what it stopped.
     */
    public suspend fun request(id: MediaItemId): DownloadRequest?

    public suspend fun remove(id: MediaItemId)

    public suspend fun rememberSkipSegments(id: MediaItemId, segments: List<SkipSegment>)

    /**
     * Every row, with the item it is about — whatever state it is in.
     *
     * [observeAll] gives states with no items and [observeDownloaded] gives items only for finished
     * downloads, so anything that is running or has failed could be counted but not NAMED. The
     * Library's in-progress row proved it: with no title available it printed the raw media id, so
     * a downloading video appeared as `chxbS3N3Llc`.
     */
    public fun observeRecords(): Flow<List<DownloadRecord>>
}

/** One download row: what it is about, and how it is getting on. */
public data class DownloadRecord(val item: PlayableItem, val state: DownloadState)

/** A download as it was asked for: the item, and whether only its audio was wanted. */
public data class DownloadRequest(val item: PlayableItem, val audioOnly: Boolean)
