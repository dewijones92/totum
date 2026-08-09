package com.dewijones92.totum.ytdlp.process

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ChannelResult
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.EngineVersions
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.bridge.parseChannel
import com.dewijones92.totum.ytdlp.bridge.parseExtraction
import com.dewijones92.totum.ytdlp.bridge.parseSearch
import com.dewijones92.totum.ytdlp.bridge.parseVersions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The engine on a desktop: the same bridge script, run as a subprocess against the system
 * Python instead of an embedded one.
 *
 * Deliberately NOT a second extractor. Everything that makes extraction work on a phone was
 * learned the hard way — the `android` player-client fallback for made-for-kids videos, the
 * JS-runtime handling, the shape of the JSON — and all of it lives in `totum_ytdlp.py`, which
 * this runs unchanged. The only difference is how Python is reached.
 *
 * What the desktop gets for free: a real JavaScript runtime, so yt-dlp solves its own `n`
 * parameters and the whole quality ladder is available without the QuickJS machinery the phone
 * needs.
 */
public class ProcessYtDlpEngine(
    private val runner: CommandRunner = SystemCommandRunner(),
) : YtDlpEngine {

    override suspend fun versions(): EngineVersions = parseVersions(run("versions"))

    override suspend fun extract(url: HttpUrl): ExtractionResult =
        when (val output = runOrNull("extract", url.value)) {
            null -> ExtractionResult.Failure.Network("could not run the extractor")
            else -> parseExtraction(url, output)
        }

    override suspend fun searchVideos(query: String, maxResults: Int): VideoSearchResult =
        when (val output = runOrNull("search", query, maxResults.toString())) {
            null -> VideoSearchResult.Failure("could not run the extractor")
            else -> parseSearch(output)
        }

    override suspend fun fetchChannel(url: HttpUrl, maxVideos: Int): ChannelResult =
        when (val output = runOrNull("channel", url.value, maxVideos.toString())) {
            null -> ChannelResult.Failure.Network("could not run the extractor")
            else -> parseChannel(url, output)
        }

    /**
     * Not needed here, and saying so rather than pretending.
     *
     * `n` is solved by yt-dlp itself on a desktop, because there is a real JavaScript runtime.
     * The phone has none, which is the only reason this is on the interface at all.
     */
    override suspend fun solveN(challenges: List<String>, playerUrl: String): Map<String, String> {
        Diag.log("engine", "solveN not needed off Android — yt-dlp solves its own with a real JS runtime")
        return emptyMap()
    }

    /** Downloading is the app's job; the CLI streams. Refused loudly rather than half-built. */
    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Failed(ExtractionResult.Failure.Extractor("this engine does not download")))
    }

    private suspend fun run(vararg args: String): String = runner.run(args.toList())

    /** Null when the process itself could not be run — a missing python, not a failed extraction. */
    private suspend fun runOrNull(vararg args: String): String? =
        runCatching { runner.run(args.toList()) }.getOrElse { failure ->
            Diag.warn("engine", "the extractor could not be run: ${failure.message}")
            null
        }
}
