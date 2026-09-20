package com.dewijones92.totum.support

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits that say WHICH item they are waiting for.
 *
 * `controller.state` describes whatever the player currently holds, and between two tests that
 * is not necessarily what the test just asked for. `queue.playNow` returns as soon as the queue
 * row is in place; resolving a YouTube URL on a cold emulator took **27 seconds** in the CI run
 * of 2026-09-20, and for all of it the previous item was still playing. A wait for
 * `isPlaying == true` was satisfied instantly by that item, and the duration read a line later
 * was ITS duration.
 *
 * That is how `SeekDeepIntoALongVideoTest` came to fail with "the fixture should be long enough
 * … but it reported 1330245ms". 1,330,245ms is 22:10, which is not the 96:45 of NASA's "Cosmic
 * Dawn"; it is the length of a video called "School Stories That Sound Fake But Actually
 * Happened", which the app had started on its own when the previous test's item reached its end
 * and autoplay looked for something related. The assertion was true, the diagnosis it invited
 * was "the fixture changed", and the fixture had not changed.
 *
 * So every wait here is scoped to an id. A leak then fails as "it is playing something else",
 * which is a sentence somebody can act on.
 */
object PlaybackWaits {

    const val POLL_MS = 250L

    /** The state, but only once it is describing [itemId]. Null if that never happens. */
    suspend fun awaitStateOf(
        controller: PlaybackController,
        itemId: MediaItemId,
        timeoutMs: Long,
        until: (PlaybackState) -> Boolean,
    ): PlaybackState? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val state = controller.state.value
            if (state != null && state.itemId == itemId && until(state)) return@withTimeoutOrNull state
            delay(POLL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /** What the player is on right now, for a failure message that names the impostor. */
    fun whatIsActuallyPlaying(controller: PlaybackController): String =
        controller.state.value
            ?.let { "\"${it.title}\" (${it.itemId.value}), duration ${it.durationMs}ms" }
            ?: "nothing"
}
