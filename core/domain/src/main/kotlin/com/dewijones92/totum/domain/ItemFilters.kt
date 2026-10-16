package com.dewijones92.totum.domain

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

public enum class DownloadedFilter { ANY, DOWNLOADED, NOT_DOWNLOADED }

public enum class LengthFilter(internal val from: Duration?, internal val until: Duration?) {
    ANY(null, null),
    UNDER_10(null, 10.minutes),
    FROM_10_TO_30(10.minutes, 30.minutes),
    FROM_30_TO_60(30.minutes, 60.minutes),
    OVER_60(60.minutes, null),
}

public enum class KindFilter { ANY, MUSIC, TALK, VIDEO }

public enum class PublishedWithin(internal val span: Duration?) {
    ANY(null),
    DAY(1.days),
    WEEK(7.days),
    MONTH(30.days),
}

public data class ItemFilters(
    val downloaded: DownloadedFilter = DownloadedFilter.ANY,
    val length: LengthFilter = LengthFilter.ANY,
    val kind: KindFilter = KindFilter.ANY,
    val within: PublishedWithin = PublishedWithin.ANY,
) {
    val activeCount: Int
        get() = listOf(
            downloaded != DownloadedFilter.ANY,
            length != LengthFilter.ANY,
            kind != KindFilter.ANY,
            within != PublishedWithin.ANY,
        ).count { it }

    val isActive: Boolean get() = activeCount > 0

    public fun accepts(item: MediaItem, downloaded: Boolean, now: Instant): Boolean =
        acceptsDownload(downloaded) &&
            acceptsLength(item.duration) &&
            acceptsKind(item) &&
            acceptsAge(item.publishedAt, now)

    private fun acceptsDownload(onDisk: Boolean): Boolean = when (downloaded) {
        DownloadedFilter.ANY -> true
        DownloadedFilter.DOWNLOADED -> onDisk
        DownloadedFilter.NOT_DOWNLOADED -> !onDisk
    }

    private fun acceptsLength(duration: Duration?): Boolean {
        if (length == LengthFilter.ANY) return true
        duration ?: return false
        return (length.from == null || duration >= length.from) && (length.until == null || duration < length.until)
    }

    private fun acceptsKind(item: MediaItem): Boolean = when (kind) {
        KindFilter.ANY -> true
        KindFilter.MUSIC -> item.listening == Listening.MUSIC
        KindFilter.TALK -> item.listening == Listening.TALK
        KindFilter.VIDEO -> item.pillar == MediaKind.VIDEO && item.listening == Listening.TALK
    }

    private fun acceptsAge(publishedAt: Instant?, now: Instant): Boolean {
        val span = within.span ?: return true
        publishedAt ?: return false
        return publishedAt >= now.minusSeconds(span.inWholeSeconds)
    }
}
