package com.dewijones92.totum.ytdlp.chaquopy

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.ytdlp.ChannelResult
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.EngineVersions
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.bridge.parseChannel
import com.dewijones92.totum.ytdlp.bridge.parseDownloadCompletion
import com.dewijones92.totum.ytdlp.bridge.parseExtraction
import com.dewijones92.totum.ytdlp.bridge.parseSearch
import com.dewijones92.totum.ytdlp.bridge.parseSolvedN
import com.dewijones92.totum.ytdlp.bridge.parseVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The real [YtDlpEngine]: yt-dlp running on an embedded CPython runtime
 * (Chaquopy). All Python calls happen off the main thread on [dispatcher].
 */
public class ChaquopyYtDlpEngine(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    updateCacheDir: File? = null,
) : YtDlpEngine {

    private val appContext = context.applicationContext
    private val updateCache = updateCacheDir?.let { YtDlpUpdateCache(it) }

    private val python: Python by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        Python.getInstance()
    }

    private val bridge by lazy {
        // Shadow the bundled yt-dlp with a runtime-downloaded wheel if one is
        // cached, BEFORE totum_ytdlp does `import yt_dlp`. A wheel that fails to
        // import is dropped so the bundled copy is used and not retried.
        val wheel = updateCache?.activeWheelPath()
        val used = python.getModule("totum_bootstrap").callAttr("activate", wheel).toString()
        if (wheel != null && used != "true") updateCache?.delete(wheel)
        python.getModule("totum_ytdlp")
    }

    /** Directory yt-dlp is pointed at for ffmpeg; null if not bundled for this ABI. */
    private val ffmpegLocation: String? by lazy { FfmpegBinary.locationDir(appContext) }

    /**
     * Hands yt-dlp the bundled QuickJS once, rather than per call: the path never changes for
     * an install, and yt-dlp reads it out of its options on every extraction anyway.
     */
    private val jsRuntimeConfigured: Boolean by lazy {
        val path = QuickJsBinary.executablePath(appContext)
        bridge.callAttr("configure_js_runtime", path)
        Diag.log("engine", "JS runtime: ${path ?: "none bundled for this ABI — formats will be missing"}")
        true
    }

    override suspend fun versions(): EngineVersions = withContext(dispatcher) {
        parseVersions(bridge.callAttr("versions").toString())
    }

    override suspend fun extract(url: HttpUrl): ExtractionResult = withContext(dispatcher) {
        check(jsRuntimeConfigured)
        timed("extract ${url.value}") {
            parseExtraction(url, bridge.callAttr("extract", url.value).toString()).also { it.reportNotes() }
        }
    }

    /**
     * Says out loud anything yt-dlp lost during a SUCCESSFUL extraction.
     *
     * A degraded success is the failure mode with no symptom: the same video produced 33 durable
     * formats on one run of this device and none on the next, and nothing in any report could say why
     * because `no_warnings` had thrown the explanation away. Logged as a warning because a missing
     * JavaScript runtime or an abandoned client is the difference between a stream that plays to the end
     * and one that stops a megabyte in.
     */
    private fun ExtractionResult.reportNotes() {
        val notes = (this as? ExtractionResult.Success)?.notes.orEmpty()
        if (notes.isEmpty()) return
        Diag.warn("engine", "yt-dlp reported ${notes.size} note(s) while extracting: ${notes.joinToString(" | ")}")
    }

    override suspend fun searchVideos(query: String, maxResults: Int): VideoSearchResult =
        withContext(dispatcher) {
            timed("search \"$query\"") { parseSearch(bridge.callAttr("search", query, maxResults).toString()) }
        }

    override suspend fun fetchChannel(url: HttpUrl, maxVideos: Int): ChannelResult =
        withContext(dispatcher) {
            timed("channel ${url.value}") {
                parseChannel(url, bridge.callAttr("channel", url.value, maxVideos).toString())
            }
        }

    override suspend fun solveN(challenges: List<String>, playerUrl: String): Map<String, String> =
        withContext(dispatcher) {
            check(jsRuntimeConfigured)
            timed("solve ${challenges.size} n parameter(s)") {
                parseSolvedN(bridge.callAttr("solve_n", challenges.toTypedArray(), playerUrl).toString())
            }
        }

    /**
     * Times every trip into Python and records it: these are the app's slowest calls and
     * the ones a user is most likely to be waiting on. A Python-side error used to reach
     * the caller with no trace of where it came from.
     *
     * The catch is deliberately broad because Python's errors are not a Kotlin type we
     * can enumerate. Nothing is swallowed — every exception is rethrown unchanged.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> timed(what: String, block: () -> T): T {
        val startedAt = System.currentTimeMillis()
        return try {
            block().also {
                val took = System.currentTimeMillis() - startedAt
                Vitals.add("engine.callMs", took)
                Vitals.add("engine.calls")
                Diag.log("engine", "$what in ${took}ms")
            }
        } catch (e: CancellationException) {
            // Navigating away mid-search is not a failure and must not be counted as one.
            Diag.log("engine", "$what cancelled after ${System.currentTimeMillis() - startedAt}ms")
            throw e
        } catch (e: Exception) {
            Vitals.add("engine.failures")
            Diag.warn("engine", "$what threw after ${System.currentTimeMillis() - startedAt}ms", e)
            throw e
        }
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = channelFlow {
        check(jsRuntimeConfigured)
        trySend(DownloadEvent.Started(request.url))
        // yt-dlp calls the hook synchronously on the download thread. A plain
        // flow{} would reject emissions from there ("flow invariant violated"),
        // so the channel — safe to send to from any thread — carries them out.
        val listener = object : ProgressListener {
            override fun onProgress(downloadedBytes: Long, totalBytes: Long, etaSeconds: Long) {
                trySend(
                    DownloadEvent.Progress(
                        bytesDownloaded = downloadedBytes,
                        totalBytes = totalBytes.takeIf { it > 0 },
                        etaSeconds = etaSeconds,
                    ),
                )
            }
        }
        // Named because a download's extraction is invisible otherwise: yt-dlp does its own
        // extract_info inside the download, so a report showed two ~12s extractions per
        // queued play with nothing to say the second was the downloader's.
        Diag.log("download", "extracting for download: ${request.url.value.takeLast(ID_CHARS)}")
        val resultJson = bridge.callAttr(
            "download",
            request.url.value,
            request.targetDirectory.absolutePath,
            request.formatId,
            listener,
            ffmpegLocation,
            request.sponsorBlockCategories.joinToString(","),
        ).toString()
        trySend(parseDownloadCompletion(request.url, resultJson) { File(it) })
    }.buffer(Channel.UNLIMITED).flowOn(dispatcher)
}

/** Enough of a watch URL to recognise the video in a log line. */
private const val ID_CHARS = 11

/** Called from Python (yt-dlp progress hook) via Chaquopy's Java proxying. */
public interface ProgressListener {
    public fun onProgress(downloadedBytes: Long, totalBytes: Long, etaSeconds: Long)
}
