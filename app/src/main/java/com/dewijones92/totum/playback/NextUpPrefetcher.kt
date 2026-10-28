package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Resolves the next video shortly before the current one ends, so the gap between tracks is
 * silence you do not hear.
 *
 * Videos resolve just-in-time, which is right — a queue of sixty would otherwise pre-extract
 * sixty URLs that expire before you reach them. But it meant the extraction happened *after* the
 * previous item finished, in the silence. Measured on a real device (0.1.201): a video ended at
 * 00:28:55 and the next did not start until 00:29:02, and the whole seven seconds was one
 * `extract … in 7187ms`.
 *
 * So: same just-in-time rule, moved a minute earlier. The work happens while something is still
 * playing, and the resolve that follows finds it already done.
 *
 * App-scoped rather than driven from a screen, for the same reason the advance is: it has to keep
 * working with the phone in a pocket, which is exactly when a seven-second gap is most annoying
 * and least explicable.
 *
 * @param nextUp what playing on would start, without starting it.
 * @param prefetch resolves and caches; must be cheap to call again and must never throw.
 */
internal class NextUpPrefetcher(
    private val states: Flow<PlaybackState?>,
    private val nextUp: () -> PlayableItem?,
    private val prefetch: suspend (PlayableItem) -> Unit,
    private val scope: CoroutineScope,
    private val leadMs: Long = LEAD_MS,
) {
    /** One prefetch per item, so a seek near the end cannot fire it repeatedly. */
    private var prefetchedFor: MediaItemId? = null

    fun start() {
        scope.launch {
            states.filterNotNull().collect { state -> consider(state) }
        }
    }

    private suspend fun consider(state: PlaybackState) {
        val duration = state.durationMs ?: return
        val remaining = duration - state.positionMs
        if (remaining > leadMs || remaining < 0) return
        if (prefetchedFor == state.itemId) return

        val next = nextUp() ?: run {
            // Logged rather than silent: "nothing was prefetched" and "there was nothing to
            // prefetch" look identical afterwards, and only one of them is a bug.
            Diag.log("resolve", "${remaining}ms left and nothing queued after this")
            prefetchedFor = state.itemId
            return
        }
        // What "getting ready" means is the caller's business, and differs per source: a video is
        // resolved, a torrent's audio is remuxed on the home server, a podcast enclosure needs
        // nothing. This used to skip anything that was not a video, on the reasoning that nothing
        // else cost anything — which stopped being true when a torrent gained an audio-only URL
        // with ~25 seconds of ffmpeg behind it, and silently left that case unprepared.
        if (!worthPreparing(next)) return

        prefetchedFor = state.itemId
        Diag.log("resolve", "${remaining}ms left — getting \"${next.item.title}\" ready ahead of time")
        prefetch(next)
    }

    /**
     * Whether there is real work to do ahead of time, so the once-per-item budget is not spent on
     * an item that needs nothing — and, more importantly, so the log does not claim preparation
     * that never happened.
     */
    private fun worthPreparing(next: PlayableItem): Boolean = when (val handle = next.handle) {
        is PlayHandle.Video -> true
        is PlayHandle.LocalVideo -> false
        // Only a torrent carries one, and it is the expensive case: the home server has to seek
        // to the file and remux its audio before a single segment exists.
        is PlayHandle.Podcast -> handle.audioUrl != null
    }

    private companion object {
        /**
         * How long before the end to start: the gapless armer's lead, which is longer than the silent tail
         * that can end an item early, and comfortably longer than the ~7s extraction measured on a real
         * device, while short enough that the URL it produces is nowhere near expiry when it is used.
         */
        const val LEAD_MS = GaplessArmer.LEAD_MS
    }
}
