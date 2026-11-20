package com.dewijones92.totum.innertube.channel

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.playlists.Playlist

/**
 * Browses a YouTube channel's public tabs — Videos, Shorts, Playlists — via
 * InnerTube. Public content, so no sign-in is needed. One seam feeds the tabbed
 * channel page; videos carry their published date ("2 days ago") for free.
 */
public interface YouTubeChannel {

    /** The channel's uploads (newest first), with published dates. */
    public suspend fun videos(channelId: String, after: PageToken? = null): ChannelVideos

    /** The channel's Shorts. */
    public suspend fun shorts(channelId: String, after: PageToken? = null): ChannelVideos

    public suspend fun streams(channelId: String, after: PageToken? = null): ChannelVideos

    /**
     * Videos within this channel matching [query] — the channel's own search box.
     * Returns the same shape as [videos], so the page renders one list either way.
     */
    public suspend fun search(channelId: String, query: String, after: PageToken? = null): ChannelVideos

    /** The channel's playlists. */
    public suspend fun playlists(channelId: String, after: PageToken? = null): ChannelPlaylists
}

/**
 * Videos (or Shorts) of a channel tab; expected failures are values.
 *
 * The success case is a [Page] rather than a bare list plus a token, so channel tabs use
 * the same paging shape as every other source in the app instead of a parallel one.
 */
public sealed interface ChannelVideos {
    public data class Success(val page: Page<FeedVideo>) : ChannelVideos
    public data class Failure(val detail: String) : ChannelVideos
}

/** A channel's playlists; expected failures are values. */
public sealed interface ChannelPlaylists {
    public data class Success(val page: Page<Playlist>) : ChannelPlaylists
    public data class Failure(val detail: String) : ChannelPlaylists
}
