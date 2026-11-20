package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.player.NetworkUnreachable
import kotlinx.coroutines.delay

internal class ResolveNetwork(
    private val online: () -> Boolean,
    private val awaitOnline: suspend (timeoutMs: Long) -> Boolean,
    private val now: () -> Long,
) {
    sealed interface Retry<out T> {
        data object GaveUp : Retry<Nothing>

        data class Answer<T>(val value: T) : Retry<T>
    }

    fun isOnline(): Boolean = online()

    suspend fun waitFor(id: String, asked: String, why: String): Boolean {
        val startedAt = now()
        Diag.warn("resolve", "$id ($asked): $why; waiting up to ${WAIT_MS}ms for the network before asking again")
        val back = awaitOnline(WAIT_MS)
        Diag.log(
            "resolve",
            "$id ($asked): ${if (back) "the network answers" else "still no network"} after ${now() - startedAt}ms",
        )
        return back
    }

    suspend fun <T> askAgain(id: String, asked: String, first: NetworkUnreachable, again: suspend () -> T): Retry<T> {
        if (!waitFor(id, asked, "YouTube could not be reached (${first.detail})")) {
            Diag.warn(
                "resolve",
                "$id ($asked): the network did not return, so yt-dlp was not run through every client; " +
                    "that says nothing about the video",
            )
            return Retry.GaveUp
        }
        delay(SETTLE_MS)
        return try {
            Diag.log("resolve", "$id: asking the signed-in account again now the network answers")
            Retry.Answer(again())
        } catch (still: NetworkUnreachable) {
            Diag.warn(
                "resolve",
                "$id ($asked): YouTube is still unreachable (${still.detail}); " +
                    "yt-dlp was not run through every client, and the video is not said to be unavailable",
            )
            Retry.GaveUp
        }
    }

    private companion object {
        const val WAIT_MS = 30_000L
        const val SETTLE_MS = 2_000L
    }
}
