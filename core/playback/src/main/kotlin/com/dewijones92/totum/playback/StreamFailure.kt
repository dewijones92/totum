package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId

/**
 * A stream that stopped for a reason something can still do about, with the position to
 * resume at.
 *
 * Deliberately not the [androidx.media3.common.PlaybackException]: the listener's job is to
 * get playback going again, and everything it needs is here. Keeping Media3 out of the
 * signal is also what lets the fake controller raise one in a unit test.
 */
public data class StreamFailure(
    public val itemId: MediaItemId,
    public val positionMs: Long,
    public val reason: Reason,
    /**
     * True when a SABR stream stopped short of its stated length.
     *
     * Carried separately from [reason] because it answers a different question. [reason] says how
     * patient to be; this says which ROUTE is at fault, so the next resolve of this item can avoid it —
     * without it, recovery forgets the resolution and asks `overSabr()` again, which is the route that
     * just stalled. Reported from a real device (0.1.435): SABR served 1% of a 61-minute video.
     */
    public val sabrStalled: Boolean = false,
) {
    /**
     * Why it stopped — and therefore what would help, which is not the same for both.
     *
     * A named pair rather than a boolean because the two need opposite responses: one wants
     * a fresh URL immediately, the other wants no request at all until there is a network to
     * make it on. Retrying an [Unreachable] straight away only spends the retry budget on a
     * connection that cannot succeed.
     */
    public enum class Reason {
        /**
         * The URL's lease ran out (403/410). A freshly-resolved URL fixes it, and the
         * network is fine, so retry at once.
         */
        Expired,

        /**
         * A 403 on an address whose own lease is still hours from running out — so the stream is
         * being turned away, not timing out, and a newly-signed URL is turned away identically.
         *
         * Told apart from [Expired] by reading the `expire` the URL carries (see `leaseVerdict`).
         * They arrive as the same status code and need opposite amounts of patience: report
         * 0.1.390 spent three re-resolves at 12–18 seconds of extraction each on URLs that were
         * valid for another six hours and refused within 150ms every time, while the item's audio
         * sat downloaded on the disk. One attempt covers a bad CDN node; the rest was silence.
         */
        Rejected,

        /**
         * The connection failed — no route, DNS, reset, timeout. The player lands in IDLE
         * and, on its own, stays there **forever**: measured 2026-07-31 by black-holing
         * HTTPS mid-playback and then restoring it, the player sat at the same millisecond
         * for over three minutes with full connectivity and would never have resumed.
         *
         * Nothing was watching for this. `AutoAdvancer` waits for an end, `StallWatchdog`
         * for a frozen buffer, and the recovery only ever heard about expiry — so a tunnel
         * with the screen off silently ended the queue.
         */
        Unreachable,
    }
}
