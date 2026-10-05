package com.dewijones92.totum.data.download

import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.asPlayable
import kotlinx.coroutines.flow.Flow

/**
 * The app's single offline-downloads seam. Both pillars go through this:
 * how the bytes are fetched (podcast enclosure over HTTP vs. video via the
 * extraction engine) is chosen inside, behind [DownloadStrategy].
 */
@Suppress("TooManyFunctions")
public interface DownloadManager {

    public suspend fun learnFacts(resolved: MediaItem)

    /** Live state of every known download, keyed by item. */
    public fun observeDownloads(): Flow<Map<MediaItemId, DownloadState>>

    /**
     * Everything held offline, with the items themselves — both pillars. This is what an
     * offline library renders; it does not need to know which catalogue anything came from.
     */
    public fun observeDownloaded(): Flow<List<DownloadedMedia>>

    /**
     * Every download, with the item it is about, in whatever state it is in.
     *
     * The one stream a management screen needs: running, failed and finished all come from it, so
     * its sections cannot disagree with each other — and a row that is not finished can still show
     * a title, which [observeDownloads] could never do.
     */
    public fun observeRecords(): Flow<List<DownloadRecord>>

    /** State of a single item (defaults to NotDownloaded). */
    public fun observe(id: MediaItemId): Flow<DownloadState>

    /**
     * Every state change as it happens, with the item attached. A hot stream of
     * transitions rather than current state, for consumers that react to a download
     * *becoming* something — notifications — instead of rendering what it is.
     */
    public fun events(): Flow<DownloadEvent>

    /**
     * Starts downloading [item]; progress is observable via [observe]. Idempotent —
     * except that an existing **audio-only** download does not satisfy a request for
     * the full media, so asking for video after the queue auto-downloaded the audio
     * re-fetches rather than silently reporting "already downloaded".
     */
    public suspend fun download(item: PlayableItem, audioOnly: Boolean = false)

    /**
     * For callers holding only a feed item, whose pillar is inferred from its URL. A
     * caller that already knows how the item plays should pass the [PlayableItem] — the
     * handle carries a video's stable watch URL, which is the only thing the engine can
     * re-resolve from.
     */
    public suspend fun download(item: MediaItem, audioOnly: Boolean = false): Unit =
        download(item.asPlayable(), audioOnly)

    /**
     * Stops a download that is still running and forgets it, partial file included.
     *
     * Distinct from [delete], which is about a file you HAVE. There was no way to stop one in
     * flight at all (Dewi, 2026-08-07) — a video started by accident held the connection and the
     * disk until it finished, and on a phone that is minutes and hundreds of megabytes.
     *
     * Cancelling something not running is a no-op rather than an error: by the time a tap arrives
     * the download may have finished, and that race must not throw.
     */
    public suspend fun cancel(id: MediaItemId)

    /**
     * Starts a failed download over.
     *
     * Its own method rather than "call download again", because a caller looking at a failed row
     * holds a [DownloadedMedia]-shaped thing, not the [PlayableItem] the original request had —
     * and the record already remembers what was asked for, including whether it was audio only.
     * Re-deriving that at the call site is how a retry silently fetches the wrong thing.
     */
    public suspend fun retry(id: MediaItemId)

    /** Removes the local file and forgets the download. */
    public suspend fun delete(id: MediaItemId)
}
