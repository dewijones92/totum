package com.dewijones92.totum.importexport

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeChannelUrl
import com.dewijones92.totum.data.channel.ChannelRepository
import com.dewijones92.totum.data.channel.ChannelVideosResult
import com.dewijones92.totum.data.importexport.ImportParseResult
import com.dewijones92.totum.data.importexport.ImportedSource
import com.dewijones92.totum.data.importexport.OpmlExporter
import com.dewijones92.totum.data.importexport.SubscriptionImportParser
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.data.podcast.SubscribeResult
import com.dewijones92.totum.data.podcast.subscribedSources
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.video.AccountSubscriptions
import kotlinx.coroutines.flow.first

/** Tally of an import run, surfaced to the user as a summary. */
data class ImportSummary(
    val podcastsAdded: Int = 0,
    val channelsAdded: Int = 0,
    val alreadyPresent: Int = 0,
    val failed: Int = 0,
    val channelsSkippedSignedOut: Int = 0,
)

/** Outcome of an import; a parse failure is a value, not an exception. */
sealed interface ImportOutcome {
    data class Done(val summary: ImportSummary) : ImportOutcome
    data class ParseError(val detail: String) : ImportOutcome
}

/**
 * Applies a parsed subscription file to the app's two subscribe paths: podcast
 * feeds go through [PodcastRepository] (idempotent per URL), YouTube channels
 * through the live account ([AccountSubscriptions]). Channels are skipped —
 * and counted — while signed out, since there is nowhere to write them.
 */
class SubscriptionImporter(
    private val parser: SubscriptionImportParser,
    private val exporter: OpmlExporter,
    private val podcasts: PodcastRepository,
    private val channels: AccountSubscriptions,
    private val channelResolver: ChannelRepository,
) {

    suspend fun import(content: String): ImportOutcome {
        val sources = when (val parsed = parser.parse(content)) {
            is ImportParseResult.Success -> parsed.sources
            is ImportParseResult.Failure -> return ImportOutcome.ParseError(parsed.detail)
        }
        var summary = ImportSummary()
        val signedIn = channels.signedIn.value
        for (source in sources) {
            summary = when (source) {
                is ImportedSource.Podcast -> summary.applyPodcast(source)
                is ImportedSource.YouTubeChannel ->
                    if (signedIn) {
                        summary.applyChannel(source)
                    } else {
                        summary.copy(
                            channelsSkippedSignedOut = summary.channelsSkippedSignedOut + 1,
                        )
                    }
            }
        }
        return ImportOutcome.Done(summary)
    }

    suspend fun exportOpml(): String {
        return exporter.export(subscribedSources(podcasts, channels.channels).first())
    }

    private suspend fun ImportSummary.applyPodcast(source: ImportedSource.Podcast): ImportSummary =
        when (podcasts.subscribe(source.feedUrl)) {
            is SubscribeResult.Subscribed -> copy(podcastsAdded = podcastsAdded + 1)
            is SubscribeResult.AlreadySubscribed -> copy(alreadyPresent = alreadyPresent + 1)
            is SubscribeResult.Failure -> copy(failed = failed + 1)
        }

    private suspend fun ImportSummary.applyChannel(source: ImportedSource.YouTubeChannel): ImportSummary {
        val channelUrl = resolveChannelUrl(source.channelUrl) ?: return copy(failed = failed + 1)
        val resolved = MediaSource.VideoChannel(SourceId(channelUrl.value), source.title, channelUrl)
        if (channels.isSubscribed(resolved.id)) return copy(alreadyPresent = alreadyPresent + 1)
        // Count as added only if the write actually persisted to the account (it can revert).
        return if (channels.setSubscribed(resolved, subscribed = true)) {
            copy(channelsAdded = channelsAdded + 1)
        } else {
            copy(failed = failed + 1)
        }
    }

    /** A `/channel/UC…` URL is used as-is; a handle URL is resolved to one via the extractor. */
    private suspend fun resolveChannelUrl(url: HttpUrl): HttpUrl? {
        if (url.value.contains("/channel/")) return url
        return when (val result = channelResolver.fetchChannelVideos(url)) {
            is ChannelVideosResult.Success -> youTubeChannelUrl(result.channelId)
            is ChannelVideosResult.Failure -> null
        }
    }
}
