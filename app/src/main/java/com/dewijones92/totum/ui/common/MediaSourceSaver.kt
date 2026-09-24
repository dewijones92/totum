package com.dewijones92.totum.ui.common

import androidx.compose.runtime.saveable.Saver
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId

/**
 * Lets a tab remember which channel or feed you had open, across a tab switch or process
 * death.
 *
 * Sub-navigation was held in plain `remember`, so opening a feed and glancing at another
 * tab dropped you back at the list. The state holder in `AppShell` restores anything saved
 * per destination — but only what is actually *saveable*, and a domain object is not, so
 * these needed a saver before the shell could bring them back.
 *
 * One saver for both pillars: a channel and a feed differ only in which URL they carry, so
 * a video tab and a podcast tab remember where they were the same way. The encoding leads
 * with a tag naming the variant, which is what makes restoring the right type possible.
 */
private const val CHANNEL = "channel"
private const val FEED = "feed"

/** Absent optional URLs travel as this rather than being dropped, so positions stay fixed. */
private const val NONE = ""

// Named because the encoding is positional: a shifted index would silently restore a title
// as a URL rather than fail.
private const val TAG = 0
private const val ID = 1
private const val TITLE = 2
private const val URL = 3
private const val WEBSITE = 4
private const val ARTWORK = 5

private fun encode(source: MediaSource): List<String> = when (source) {
    is MediaSource.VideoChannel ->
        listOf(CHANNEL, source.id.value, source.title, source.channelUrl.value, NONE, source.artworkUrl.encoded())

    is MediaSource.PodcastFeed ->
        listOf(
            FEED,
            source.id.value,
            source.title,
            source.feedUrl.value,
            source.websiteUrl.encoded(),
            source.artworkUrl.encoded(),
        )
}

private fun HttpUrl?.encoded(): String = this?.value ?: NONE

private fun List<String>.optionalUrl(index: Int): HttpUrl? =
    getOrNull(index)?.takeIf { it != NONE }?.let(HttpUrl::parse)

private fun decode(saved: List<String>): MediaSource? {
    val id = saved.getOrNull(ID) ?: return null
    val title = saved.getOrNull(TITLE) ?: return null
    // A URL that was valid when saved parses when restored; treating a failure as "nothing
    // open" simply lands on the list, which is the same place a cold start would.
    val url = saved.getOrNull(URL)?.let(HttpUrl::parse) ?: return null
    return when (saved.getOrNull(TAG)) {
        CHANNEL -> MediaSource.VideoChannel(SourceId(id), title, url, artworkUrl = saved.optionalUrl(ARTWORK))
        FEED -> MediaSource.PodcastFeed(
            id = SourceId(id),
            title = title,
            feedUrl = url,
            websiteUrl = saved.optionalUrl(WEBSITE),
            artworkUrl = saved.optionalUrl(ARTWORK),
        )

        else -> null
    }
}

/** Typed edges over one shared encoding, so a screen keeps its precise type. */
private inline fun <reified T : MediaSource> mediaSourceSaver(): Saver<T?, Any> = Saver(
    save = { it?.let(::encode) ?: emptyList<String>() },
    restore = { saved -> (saved as? List<*>)?.filterIsInstance<String>()?.let(::decode) as? T },
)

internal val PodcastFeedSaver: Saver<MediaSource.PodcastFeed?, Any> = mediaSourceSaver()

internal val VideoChannelSaver: Saver<MediaSource.VideoChannel?, Any> = mediaSourceSaver()

internal val MediaSourceSaver: Saver<MediaSource?, Any> = mediaSourceSaver()
