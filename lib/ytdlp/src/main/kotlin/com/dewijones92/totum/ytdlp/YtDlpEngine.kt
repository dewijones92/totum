package com.dewijones92.totum.ytdlp

import com.dewijones92.totum.common.HttpUrl
import kotlinx.coroutines.flow.Flow

/**
 * The library's single entry point: a yt-dlp media extraction and download
 * engine.
 *
 * Implementations: a real engine backed by an embedded CPython runtime
 * running yt-dlp (in progress), and [com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine]
 * for tests, previews, and development against the boundary.
 */
public interface YtDlpEngine {

    /** Versions of the moving parts, for diagnostics and update decisions. */
    public suspend fun versions(): EngineVersions

    /**
     * Extracts metadata (title, formats, …) for [url] without downloading.
     * Expected failures are values — see [ExtractionResult.Failure].
     */
    public suspend fun extract(url: HttpUrl): ExtractionResult

    /** Searches for videos (yt-dlp `ytsearch`), returning at most [maxResults] entries. */
    public suspend fun searchVideos(query: String, maxResults: Int): VideoSearchResult

    /** Resolves a channel URL to its name and up to [maxVideos] recent uploads. */
    public suspend fun fetchChannel(url: HttpUrl, maxVideos: Int): ChannelResult

    /**
     * Deobfuscates YouTube `n` throttling parameters using the engine's JavaScript runtime.
     *
     * Here rather than in a YouTube-specific library because the JS runtime is the engine's —
     * this exposes a capability the engine already has, and keeps `:lib:innertube` independent
     * of `:lib:ytdlp` as both are meant to be separately publishable. The app owns the wiring.
     *
     * Unsolvable challenges are ABSENT from the result rather than echoed back: a passed-through
     * value produces a URL that 403s at playback time, which is strictly worse than knowing now.
     */
    public suspend fun solveN(challenges: List<String>, playerUrl: String): Map<String, String>

    public suspend fun warmUp()

    /**
     * Downloads media described by [request]. The returned flow is cold:
     * collecting starts the download, cancelling the collection cancels it.
     * Terminal events are [DownloadEvent.Completed] and [DownloadEvent.Failed].
     */
    public fun download(request: DownloadRequest): Flow<DownloadEvent>
}

/** Versions of the engine's moving parts. */
public data class EngineVersions(
    val ytDlp: String,
    val python: String,
)
