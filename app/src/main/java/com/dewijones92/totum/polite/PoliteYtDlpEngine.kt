package com.dewijones92.totum.polite

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.ytdlp.ChannelResult
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine

class PoliteYtDlpEngine(
    private val delegate: YtDlpEngine,
    private val gate: PoliteGate,
    private val botBackOff: BotBackOff,
) : YtDlpEngine by delegate {

    override suspend fun extract(url: HttpUrl): ExtractionResult {
        val what = "yt-dlp extract ${url.value.substringAfter("watch?v=").take(VIDEO_ID_LENGTH)}"
        if (!botBackOff.allows(what)) {
            return ExtractionResult.Failure.Extractor("$BACKING_OFF (Sign in to confirm you’re not a bot, earlier)")
        }
        val result = gate.passing(what) { delegate.extract(url) }
        when {
            result is ExtractionResult.Success -> botBackOff.served(what)
            result.toString().contains("not a bot", ignoreCase = true) -> botBackOff.refused(what)
        }
        return result
    }

    override suspend fun searchVideos(query: String, maxResults: Int): VideoSearchResult =
        gate.passing("yt-dlp search") { delegate.searchVideos(query, maxResults) }

    override suspend fun fetchChannel(url: HttpUrl, maxVideos: Int): ChannelResult =
        gate.passing("yt-dlp channel") { delegate.fetchChannel(url, maxVideos) }

    companion object {
        const val BACKING_OFF = "anonymous YouTube is backing off after a bot check"
        private const val VIDEO_ID_LENGTH = 11
    }
}
