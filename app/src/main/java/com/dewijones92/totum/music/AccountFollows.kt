package com.dewijones92.totum.music

import com.dewijones92.totum.common.youTubeChannelUrl
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.video.AccountSubscriptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class AccountFollows(private val subscriptions: AccountSubscriptions) : MusicFavourites.Follows {

    override val followedChannelIds: Flow<Set<String>?> = combine(
        subscriptions.channels,
        subscriptions.signedIn,
        subscriptions.loaded,
    ) { channels, signedIn, loaded ->
        if (signedIn && loaded) channels.mapNotNull { it.youTubeChannelId }.toSet() else null
    }

    override suspend fun set(channelId: String, name: String, followed: Boolean): Boolean {
        val url = youTubeChannelUrl(channelId) ?: return false
        return subscriptions.setSubscribed(MediaSource.VideoChannel(SourceId(url.value), name, url), followed)
    }
}
