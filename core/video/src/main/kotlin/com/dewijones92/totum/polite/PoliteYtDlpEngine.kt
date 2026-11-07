package com.dewijones92.totum.polite

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.ytdlp.ChannelResult
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

class PoliteYtDlpEngine(
    private val delegate: YtDlpEngine,
    private val gate: PoliteGate,
    private val botBackOff: BotBackOff,
) : YtDlpEngine by delegate {

    override suspend fun extract(url: HttpUrl): ExtractionResult {
        val what = "yt-dlp extract ${videoIdOf(url)}"
        if (!botBackOff.allows(what)) {
            return ExtractionResult.Failure.Extractor("$BACKING_OFF (Sign in to confirm you’re not a bot, earlier)")
        }
        val result = gate.passing(what) { delegate.extract(url) }
        if (result is ExtractionResult.Success) botBackOff.served(what) else noteRefusal(what, result.toString())
        return result
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = channelFlow {
        val what = "yt-dlp download ${videoIdOf(request.url)}"
        if (!botBackOff.allows(what)) {
            send(DownloadEvent.Failed(ExtractionResult.Failure.Extractor(BACKING_OFF)))
            return@channelFlow
        }
        val first = mutableListOf<DownloadEvent>()
        var served = false
        fun see(event: DownloadEvent) {
            when {
                event is DownloadEvent.Failed -> noteRefusal(what, event.reason.toString())
                event !is DownloadEvent.Started && !served -> {
                    served = true
                    botBackOff.served(what)
                }
            }
        }
        val rest = gate.passing(what) {
            val source = produce(capacity = Channel.UNLIMITED) { delegate.download(request).collect { send(it) } }
            for (event in source) {
                see(event)
                first += event
                if (event !is DownloadEvent.Started) break
            }
            source
        }
        first.forEach { send(it) }
        for (event in rest) {
            see(event)
            send(event)
        }
    }

    override suspend fun solveN(challenges: List<String>, playerUrl: String): Map<String, String> =
        gate.passing("yt-dlp solve n") { delegate.solveN(challenges, playerUrl) }

    override suspend fun solveSignatures(challenges: List<String>, playerUrl: String): Map<String, String> =
        gate.passing("yt-dlp solve signature") { delegate.solveSignatures(challenges, playerUrl) }

    override suspend fun searchVideos(query: String, maxResults: Int): VideoSearchResult =
        gate.passing("yt-dlp search") { delegate.searchVideos(query, maxResults) }

    override suspend fun fetchChannel(url: HttpUrl, maxVideos: Int): ChannelResult =
        gate.passing("yt-dlp channel") { delegate.fetchChannel(url, maxVideos) }

    private fun noteRefusal(what: String, said: String) {
        when {
            said.contains("not a bot", ignoreCase = true) -> botBackOff.refused(what)
            TOO_MANY.containsMatchIn(said) -> gate.tooManyRequests(what, retryAfterMs = null)
        }
    }

    private fun videoIdOf(url: HttpUrl) = url.value.substringAfter("watch?v=").take(VIDEO_ID_LENGTH)

    companion object {
        const val BACKING_OFF = "anonymous YouTube is backing off after a bot check"
        private const val VIDEO_ID_LENGTH = 11
        private val TOO_MANY = Regex("""HTTP Error 429|Too Many Requests""", RegexOption.IGNORE_CASE)
    }
}
