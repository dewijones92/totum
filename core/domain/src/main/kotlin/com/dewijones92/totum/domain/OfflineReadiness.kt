package com.dewijones92.totum.domain

/**
 * How much of the queue you could actually listen to with no signal.
 *
 * Dewi, 2026-08-02: *"I expect everything in the queue to have an auto download for audio and
 * for it to work offline … I expect the gui / labels etc to be very very clear"*. The machinery
 * already did most of this; what was missing was any way to SEE it. A diagnostics report could
 * say `downloading=0; failed=4` while the app itself showed a subtle headphones glyph per row
 * and no answer at all to "is my queue ready?".
 *
 * Pure, so the counting is unit-tested and the screen only has to render it.
 */
public data class OfflineReadiness(
    /** Downloaded and playable with no signal. */
    public val ready: Int,
    /** Fetching now. */
    public val downloading: Int,
    /** Not started, or a failure worth retrying — both resolve themselves in time. */
    public val waiting: Int,
    /**
     * Permanently undownloadable: members-only, removed, region-blocked.
     *
     * Counted apart from [waiting] because they are the only ones needing a decision. Kept in
     * the queue and marked rather than removed (Dewi's choice, 2026-08-02) — they still play
     * perfectly well with signal, and deleting somebody's queue items to make a number tidy is
     * not the app's call.
     */
    public val unavailableOffline: Int,
    /**
     * Not fetched automatically, and downloadable by hand — today, a torrent.
     *
     * Its own count because it is neither of the two things it would otherwise be lumped with. It
     * is not *waiting*: nothing will ever fetch it, so "still to fetch" would be a promise the app
     * has no intention of keeping. And it is not *unavailable*: tapping Download works. The queue
     * skips these because an automatic fetch exists to make the queue LISTENABLE and a torrent has
     * no audio-only fetch — so the alternative is quietly pulling a 1.7GB film per queue entry.
     */
    public val notAutomatic: Int = 0,
) {
    public val total: Int get() = ready + downloading + waiting + unavailableOffline + notAutomatic

    /** Nothing left to wait for: everything is either here or never coming. */
    public val settled: Boolean get() = downloading == 0 && waiting == 0

    /** True when every item that CAN be offline is, which is the honest definition of done. */
    public val complete: Boolean get() = settled && ready > 0

    public companion object {
        /** Counts [items] by what [stateOf] says about each. */
        public fun of(
            items: List<MediaItemId>,
            stateOf: (MediaItemId) -> DownloadState,
            /** False for an item nothing will fetch on its own; see [notAutomatic]. */
            fetchedAutomatically: (MediaItemId) -> Boolean = { true },
        ): OfflineReadiness {
            var ready = 0
            var downloading = 0
            var waiting = 0
            var unavailable = 0
            var notAutomatic = 0
            items.forEach { id ->
                when (val state = stateOf(id)) {
                    is DownloadState.Downloaded -> ready++
                    is DownloadState.Downloading -> downloading++
                    // A failure that could succeed later is still "waiting" to a person: the app
                    // retries it, so telling them it failed would ask for a decision they do not
                    // need to make.
                    is DownloadState.Failed -> if (state.isPermanent) unavailable++ else waiting++
                    // A manual download makes it ready like anything else, so only a not-yet-fetched
                    // one counts here — and it counts as its own thing rather than as waiting.
                    DownloadState.NotDownloaded -> if (fetchedAutomatically(id)) waiting++ else notAutomatic++
                }
            }
            return OfflineReadiness(ready, downloading, waiting, unavailable, notAutomatic)
        }
    }
}

/**
 * Whether this item cannot be played right now because it is not on the device and there is no
 * network — the one row state that needs saying out loud rather than being discovered.
 *
 * The queue already SKIPS these offline rather than spending a stall budget on each (see
 * `PlaybackQueue`), which is right and also invisible: an item silently passed over reads as the
 * app losing your place. A row that says why costs nothing and answers the question.
 */
public fun PlayableItem.unavailableOfflineNow(state: DownloadState, offline: Boolean): Boolean =
    offline && !playsWithNoSignal(state)

public fun PlayableItem.playsWithNoSignal(state: DownloadState): Boolean =
    routeNow(
        onDisk = (state as? DownloadState.Downloaded)?.let { LocalCopy(it.localPath, it.audioOnly) },
        offline = true,
        audioPreferred = false,
    ) is PlayRoute.File
