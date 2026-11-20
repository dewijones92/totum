package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.util.concurrent.ConcurrentHashMap

public class TvStartWatch(
    private val later: (Long, () -> Unit) -> Unit = castLater("cast-start-watch"),
    private val now: () -> Long = System::currentTimeMillis,
    private val waitMs: Long = WAIT_MS,
    private val onStuck: (mediaId: String, token: String, waitedMs: Long) -> Unit,
) {
    private class Seen(@Volatile var pictureAt: Long = NOT_YET, @Volatile var soundAt: Long = NOT_YET)

    private val seen = ConcurrentHashMap<String, Seen>()

    public fun pictureFetched(mediaId: String, token: String) {
        val state = seen.getOrPut(token) { Seen() }
        synchronized(state) {
            if (state.pictureAt != NOT_YET) return
            state.pictureAt = now()
            if (state.soundAt != NOT_YET) {
                Diag.log("cast", "$mediaId: the TV asked for sound before its picture; nothing to watch for")
                return
            }
        }
        later(waitMs) { check(mediaId, token, state) }
    }

    public fun soundFetched(token: String) {
        val state = seen.getOrPut(token) { Seen() }
        synchronized(state) {
            if (state.soundAt == NOT_YET) state.soundAt = now()
        }
    }

    public fun started(token: String): Boolean = seen[token]?.soundAt?.let { it != NOT_YET } == true

    public fun forget(token: String) {
        seen.remove(token)
    }

    public fun clear() {
        seen.clear()
    }

    private fun check(mediaId: String, token: String, state: Seen) {
        if (seen[token] !== state) return
        val stuck = synchronized(state) { state.soundAt == NOT_YET }
        if (!stuck) return
        onStuck(mediaId, token, now() - state.pictureAt)
    }

    public companion object {
        public const val WAIT_MS: Long = 8_000L
        private const val NOT_YET = Long.MIN_VALUE
    }
}
