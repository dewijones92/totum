package com.dewijones92.totum.data.group

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.channel.ChannelRepository
import com.dewijones92.totum.data.channel.ChannelVideosResult
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The video pillar's members, through the same [ChannelRepository] a channel page uses —
 * so a group shows exactly what opening each channel would show, including shorts and
 * live, and there is one fetch path to keep working rather than two.
 */
public class ChannelSourceItems(
    private val pageOf: suspend (MediaSource.VideoChannel) -> List<MediaItem>,
    private val feedOf: suspend (MediaSource.VideoChannel) -> List<MediaItem>?,
    private val dayOf: suspend (MediaItemId) -> LocalDate? = { null },
    private val clock: () -> Instant = Instant::now,
) : SourceItems {

    public constructor(
        channels: ChannelRepository,
        feedOf: suspend (MediaSource.VideoChannel) -> List<MediaItem>?,
        dayOf: suspend (MediaItemId) -> LocalDate?,
    ) : this(pageOf = { channel -> pageOf(channels, channel) }, feedOf = feedOf, dayOf = dayOf)

    override suspend fun itemsFor(source: MediaSource): List<MediaItem> {
        val channel = source as? MediaSource.VideoChannel ?: return emptyList()
        val page = pageOf(channel)
        val feed = runCatching { feedOf(channel) }
            .onFailure { Diag.warn("group", "\"${channel.title}\" feed could not be read; page dates only", it) }
            .getOrNull()
        val fed = feed.orEmpty().mapTo(mutableSetOf()) { it.id }
        val days = coroutineScope {
            page.filter { it.id !in fed }.map { item -> async { item to runCatching { dayOf(item.id) }.getOrNull() } }
                .awaitAll()
        }
        val byDay = days.mapNotNull { (item, day) ->
            day?.let {
                item.copy(publishedAt = it.atTime(NOON).toInstant(ZoneOffset.UTC))
            }
        }
        val dated = datedChannelItems(feed.orEmpty() + byDay, page, clock())
        Diag.log(
            "group",
            "\"${channel.title}\": page=${page.size} feed=${feed?.size ?: "unread"} " +
                "dayLookups=${days.size} found=${byDay.size} -> ${dated.size} item(s), " +
                "${dated.count { it.publishedAt == null }} undated",
        )
        return dated
    }
}

private val NOON: LocalTime = LocalTime.NOON

private suspend fun pageOf(channels: ChannelRepository, channel: MediaSource.VideoChannel): List<MediaItem> =
    when (val result = channels.fetchChannelVideos(channel.channelUrl)) {
        is ChannelVideosResult.Success -> result.videos
        is ChannelVideosResult.Failure -> throw GroupMemberUnreadable(result.toString())
    }

/** A member that could not be read. Carries the reason so the group can log whose it was. */
public class GroupMemberUnreadable(detail: String) : Exception(detail)
