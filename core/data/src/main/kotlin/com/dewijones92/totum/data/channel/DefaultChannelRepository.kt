package com.dewijones92.totum.data.channel

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.formatViewCount
import com.dewijones92.totum.ytdlp.ChannelResult
import com.dewijones92.totum.ytdlp.VideoSearchEntry
import com.dewijones92.totum.ytdlp.YtDlpEngine
import kotlin.time.Duration.Companion.seconds

public class DefaultChannelRepository(
    private val engine: YtDlpEngine,
    private val maxVideos: Int = DEFAULT_MAX_VIDEOS,
) : ChannelRepository {

    override suspend fun fetchChannelVideos(channelUrl: HttpUrl): ChannelVideosResult {
        val id = SourceId(channelUrl.value)
        return when (val result = engine.fetchChannel(channelUrl, maxVideos)) {
            is ChannelResult.Success ->
                ChannelVideosResult.Success(
                    channelId = result.channelId,
                    title = result.title,
                    videos = result.videos.map { it.toMediaItem(id) },
                )
            is ChannelResult.Failure.Network -> ChannelVideosResult.Failure.Network(result.detail)
            is ChannelResult.Failure.NotAChannel -> ChannelVideosResult.Failure.NotAChannel(result.url.value)
        }
    }

    private fun VideoSearchEntry.toMediaItem(sourceId: SourceId) = MediaItem(
        id = MediaItemId(id),
        sourceId = sourceId,
        title = title,
        publishedAt = null,
        duration = durationSeconds?.seconds,
        author = uploader,
        // Resolved to a stream URL on play (video URLs expire); watchUrl is the stable handle.
        thumbnailUrl = thumbnailUrl?.let(HttpUrl::parse),
        mediaUrl = watchUrl,
        viewsText = viewCount?.let(::formatViewCount),
        membersOnly = membersOnly,
    )

    private companion object {
        const val DEFAULT_MAX_VIDEOS = 20
    }
}
