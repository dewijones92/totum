package com.dewijones92.totum.ytdlp.fake

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ChannelResult
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.EngineVersions
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.VideoSearchEntry
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * An in-memory [YtDlpEngine] for tests, Compose previews, and development
 * until the embedded-CPython engine lands. Behaviour is deterministic:
 * URLs registered via [registerMedia] extract successfully; everything else
 * is [ExtractionResult.Failure.UnsupportedUrl].
 */
@Suppress("TooManyFunctions")
public class FakeYtDlpEngine : YtDlpEngine {

    private val mediaByUrl = mutableMapOf<HttpUrl, MediaMetadata>()
    private val searchResults = mutableMapOf<String, List<VideoSearchEntry>>()
    private val channels = mutableMapOf<HttpUrl, ChannelResult.Success>()

    /**
     * How many times [extract] has been called.
     *
     * Counted because "did this happen at all" is the assertion that matters for some
     * callers: a real extraction starts an embedded Python interpreter and a JS runtime, and
     * a caller reaching for it when it had the answer already cost 12.5 seconds of Dewi's
     * time per tap. A test can only defend that by checking the call never happened.
     */
    public var extractCalls: Int = 0
        private set

    /** Makes [url] extractable, returning canned [metadata]. */
    public fun registerMedia(url: HttpUrl, metadata: MediaMetadata) {
        mediaByUrl[url] = metadata
    }

    /** Makes [query] return canned [entries]; unregistered queries return no hits. */
    public fun registerSearch(query: String, entries: List<VideoSearchEntry>) {
        searchResults[query] = entries
    }

    /** Makes [url] resolve as a channel; unregistered channel URLs are NotAChannel. */
    public fun registerChannel(url: HttpUrl, channel: ChannelResult.Success) {
        channels[url] = channel
    }

    public var warmUps: Int = 0
        private set

    override suspend fun warmUp() {
        warmUps++
    }

    override suspend fun versions(): EngineVersions =
        EngineVersions(ytDlp = "fake", python = "fake")

    override suspend fun extract(url: HttpUrl): ExtractionResult {
        extractCalls++
        return doExtract(url)
    }

    private fun doExtract(url: HttpUrl): ExtractionResult =
        mediaByUrl[url]
            ?.let { ExtractionResult.Success(it) }
            ?: ExtractionResult.Failure.UnsupportedUrl(url)

    override suspend fun searchVideos(query: String, maxResults: Int): VideoSearchResult =
        VideoSearchResult.Success(searchResults[query].orEmpty().take(maxResults))

    override suspend fun fetchChannel(url: HttpUrl, maxVideos: Int): ChannelResult =
        channels[url]?.let { it.copy(videos = it.videos.take(maxVideos)) }
            ?: ChannelResult.Failure.NotAChannel(url)

    /**
     * Solved `n` parameters tests want handed back; anything absent is treated as unsolvable,
     * which is how a test drives the drop-the-format path.
     */
    public var solvedN: Map<String, String> = emptyMap()

    override suspend fun solveN(challenges: List<String>, playerUrl: String): Map<String, String> =
        solvedN.filterKeys { it in challenges }

    /** The last download asked for — lets tests assert the format selector used. */
    public var lastRequest: DownloadRequest? = null
        private set

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        lastRequest = request
        emit(DownloadEvent.Started(request.url))
        val metadata = mediaByUrl[request.url]
        if (metadata == null) {
            emit(DownloadEvent.Failed(ExtractionResult.Failure.UnsupportedUrl(request.url)))
            return@flow
        }
        val totalBytes = TOTAL_FAKE_BYTES
        var downloaded = 0L
        while (downloaded < totalBytes) {
            downloaded = (downloaded + totalBytes / PROGRESS_STEPS).coerceAtMost(totalBytes)
            emit(DownloadEvent.Progress(downloaded, totalBytes, etaSeconds = 0))
        }
        // Write a real file so download consumers can move/inspect the result.
        val file = request.targetDirectory.apply { mkdirs() }.resolve("${metadata.id}.mp4")
        file.writeBytes(ByteArray(1))
        emit(DownloadEvent.Completed(file))
    }

    public companion object {
        private const val TOTAL_FAKE_BYTES = 1_000_000L
        private const val PROGRESS_STEPS = 4

        /** A ready-made metadata sample for previews and tests. */
        public fun sampleMetadata(id: String = "sample-1"): MediaMetadata = MediaMetadata(
            id = id,
            title = "Sample video",
            uploader = "Sample channel",
            durationSeconds = 90,
            thumbnailUrl = null,
            formats = listOf(
                MediaFormat(
                    formatId = "22",
                    container = "mp4",
                    width = 1280,
                    height = 720,
                    hasVideo = true,
                    hasAudio = true,
                    fileSizeBytes = 1_000_000,
                    url = "https://cdn.example.com/$id.mp4",
                ),
                MediaFormat(
                    formatId = "140",
                    container = "m4a",
                    width = null,
                    height = null,
                    hasVideo = false,
                    hasAudio = true,
                    fileSizeBytes = 250_000,
                    url = "https://cdn.example.com/$id.m4a",
                ),
            ),
        )

        /** A ready-made search entry for previews and tests. */
        public fun sampleSearchEntry(id: String = "vid-1", title: String = "Sample result"): VideoSearchEntry =
            VideoSearchEntry(
                id = id,
                title = title,
                uploader = "Sample channel",
                durationSeconds = 90,
                watchUrl = HttpUrl.of("https://example.com/watch?v=$id"),
                thumbnailUrl = null,
            )
    }
}
