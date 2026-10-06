package com.dewijones92.totum.busy

import com.dewijones92.totum.common.Busy
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ChannelResult
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.EngineVersions
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import kotlinx.coroutines.flow.Flow

/**
 * Reports extraction work as in-flight, so the app can say it is busy during the slowest
 * thing it does.
 *
 * The other half of the global indicator, and the half that matters most: an extraction runs
 * an embedded Python interpreter and a JavaScript runtime, and on first use pays about eight
 * seconds of startup before it even begins. Those are the seconds a user spends wondering
 * whether their tap registered.
 *
 * A decorator rather than logic inside the engine, so the real engine stays a plain
 * implementation of the port and the fake needs no changes to be observable.
 *
 * [download] is passed straight through, **unreported**: it runs for minutes and has its own
 * progress row and notification, and a global indicator lit for the whole of it would mean
 * nothing. [versions] likewise — it is a startup detail nobody is waiting on.
 */
class BusyYtDlpEngine(private val delegate: YtDlpEngine) : YtDlpEngine {

    override suspend fun versions(): EngineVersions = delegate.versions()

    override suspend fun warmUp(): Unit = delegate.warmUp()

    override suspend fun extract(url: HttpUrl): ExtractionResult =
        Busy.during("extracting ${url.value.substringAfter("watch?v=").take(VIDEO_ID_LENGTH)}") {
            delegate.extract(url)
        }

    override suspend fun searchVideos(query: String, maxResults: Int): VideoSearchResult =
        Busy.during("searching") { delegate.searchVideos(query, maxResults) }

    override suspend fun fetchChannel(url: HttpUrl, maxVideos: Int): ChannelResult =
        Busy.during("loading channel") { delegate.fetchChannel(url, maxVideos) }

    /**
     * Shown as busy: solving takes seconds of JavaScript, and it happens while somebody is
     * waiting for a video to start. Silence there reads as the app having ignored the tap.
     */
    override suspend fun solveN(challenges: List<String>, playerUrl: String): Map<String, String> =
        Busy.during("preparing video") { delegate.solveN(challenges, playerUrl) }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = delegate.download(request)

    private companion object {
        const val VIDEO_ID_LENGTH = 11
    }
}
