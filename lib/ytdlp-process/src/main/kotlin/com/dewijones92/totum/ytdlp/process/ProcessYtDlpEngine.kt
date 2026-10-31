package com.dewijones92.totum.ytdlp.process

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ChannelResult
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.EngineVersions
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.FfmpegMerge
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.bridge.parseChannel
import com.dewijones92.totum.ytdlp.bridge.parseDownloadCompletion
import com.dewijones92.totum.ytdlp.bridge.parseExtraction
import com.dewijones92.totum.ytdlp.bridge.parseSearch
import com.dewijones92.totum.ytdlp.bridge.parseSolvedN
import com.dewijones92.totum.ytdlp.bridge.parseVersions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * The engine on a desktop: the same bridge script, run as a subprocess against the system
 * Python instead of an embedded one.
 *
 * Deliberately NOT a second extractor. Everything that makes extraction work on a phone was
 * learned the hard way — the `android` player-client fallback for made-for-kids videos, the
 * JS-runtime handling, the shape of the JSON — and all of it lives in `totum_ytdlp.py`, which
 * this runs unchanged. The only difference is how Python is reached.
 *
 * The JavaScript runtime is named by `TOTUM_JS_RUNTIME` (`deno:/usr/local/bin/deno`); without it
 * yt-dlp runs with none, and its URLs may not play.
 */
@Suppress("TooManyFunctions")
public class ProcessYtDlpEngine(
    private val runner: CommandRunner = SystemCommandRunner(),
    private val ffmpeg: String = System.getenv("TOTUM_FFMPEG") ?: "ffmpeg",
) : YtDlpEngine {

    override suspend fun versions(): EngineVersions = parseVersions(run("versions"))

    override suspend fun warmUp(): Unit = Unit

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

    override suspend fun solveN(challenges: List<String>, playerUrl: String): Map<String, String> =
        parseSolvedN(run("solve_n", challenges.asJsonArray(), playerUrl))

    override suspend fun solveSignatures(challenges: List<String>, playerUrl: String): Map<String, String> =
        parseSolvedN(run("solve_sig", challenges.asJsonArray(), playerUrl))

    override suspend fun mergeStreams(video: File, audio: File, output: File): String? =
        FfmpegMerge.merge(ffmpeg, video, audio, output)

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Started(request.url))
        Diag.log("download", "extracting for download: ${request.url.value}")
        var last = ""
        runner.lines(
            listOf(
                "download",
                request.url.value,
                request.targetDirectory.absolutePath,
                request.formatId.orEmpty(),
                "",
                request.sponsorBlockCategories.joinToString(","),
            ),
        ).collect { line ->
            if (line.startsWith(PROGRESS)) {
                val (done, total, eta) = line.removePrefix(PROGRESS).trim('[', ']').split(',')
                    .map { it.trim().toLongOrNull() ?: 0 }
                emit(DownloadEvent.Progress(done, total.takeIf { it > 0 }, eta))
            } else {
                last = line
            }
        }
        emit(parseDownloadCompletion(request.url, last) { File(it) })
    }

    private suspend fun run(vararg args: String): String = runner.run(args.toList())

    /** Null when the process itself could not be run — a missing python, not a failed extraction. */
    private suspend fun runOrNull(vararg args: String): String? =
        runCatching { runner.run(args.toList()) }.getOrElse { failure ->
            Diag.warn("engine", "the extractor could not be run: ${failure.message}")
            null
        }
}

private fun List<String>.asJsonArray(): String =
    joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

private const val PROGRESS = "PROGRESS "
