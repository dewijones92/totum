package com.dewijones92.totum.ytdlp

import com.dewijones92.totum.common.HttpUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes downloads yield to the work someone is actually waiting for.
 *
 * A download's first act is its own `extract_info`, and yt-dlp's engine is one embedded
 * interpreter — so a download starting at the same moment as a play is two extractions of the
 * same video competing for it. Measured on 2026-07-31: tapping a queued item logged
 * `[download] extracting for download: xx-KSozQdNU` and `[resolve] xx-KSozQdNU … for play`
 * overlapping, and the play waited **16 seconds**. The auto-downloader reacts to every queue
 * change, so the very act of starting playback is what sets its rival off.
 *
 * The ordering is wrong regardless of how much of those 16 seconds was contention (that part
 * was NOT proven — it was an x86_64 emulator with a software JS runtime, which is slow
 * anyway). Nobody is waiting on a background download; someone is always waiting on a play.
 * Deferring the download by a few seconds cannot be noticed, and letting it go first can.
 *
 * Deliberately NOT a fix for the duplication itself. Both extractions still happen, which is
 * wasted work on a phone; removing that means handing yt-dlp a pre-extracted info dict for
 * the download, which is a real change to the Python bridge and wants its own pass.
 */
public class InteractiveFirstEngine(
    private val delegate: YtDlpEngine,
    private val maxWaitMs: Long = MAX_WAIT_MS,
) : YtDlpEngine {

    private val interactive = MutableStateFlow(0)

    override suspend fun versions(): EngineVersions = delegate.versions()

    override suspend fun warmUp(): Unit = delegate.warmUp()

    override suspend fun extract(url: HttpUrl): ExtractionResult =
        asInteractive { delegate.extract(url) }

    override suspend fun searchVideos(query: String, maxResults: Int): VideoSearchResult =
        asInteractive { delegate.searchVideos(query, maxResults) }

    override suspend fun fetchChannel(url: HttpUrl, maxVideos: Int): ChannelResult =
        asInteractive { delegate.fetchChannel(url, maxVideos) }

    /** Interactive: somebody is waiting on a video that will not start until this returns. */
    override suspend fun solveN(challenges: List<String>, playerUrl: String): Map<String, String> =
        asInteractive { delegate.solveN(challenges, playerUrl) }

    /**
     * Waits for interactive work to finish, then downloads.
     *
     * Bounded by [maxWaitMs] so a steady trickle of interactive work — the next-up
     * prefetcher, a user browsing — can never starve downloads entirely. Hitting the bound
     * means the download proceeds anyway, which is the old behaviour and so no worse.
     */
    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        withTimeoutOrNull(maxWaitMs) { interactive.first { it == 0 } }
        emitAll(delegate.download(request))
    }

    private suspend fun <T> asInteractive(block: suspend () -> T): T {
        interactive.update { it + 1 }
        try {
            return block()
        } finally {
            // In a finally because a failed extraction that left the counter raised would
            // block every download for [maxWaitMs], turning one error into a stalled queue.
            interactive.update { it - 1 }
        }
    }
}

/** Atomic read-modify-write; `compareAndSet` retries, so no lock is needed. */
private inline fun MutableStateFlow<Int>.update(transform: (Int) -> Int) {
    while (true) {
        val current = value
        if (compareAndSet(current, transform(current))) return
    }
}

/** Long enough to cover a slow extraction, short enough that a stuck one is not fatal. */
private const val MAX_WAIT_MS = 60_000L
