package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeChannelUrl
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.data.source.AccountChannelLookup
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.WatchNextParser

class AccountChannel(
    private val account: YouTubeAccount,
    private val client: InnerTubeClient,
) : AccountChannelLookup {

    override suspend fun channelOf(watchUrl: HttpUrl): MediaSource.VideoChannel? {
        val id = watchUrl.youTubeVideoId() ?: return null
        val token = (runCatching { account.accessToken() }.getOrNull() as? AccessTokenResult.Available)?.token
            ?: return null.also { Diag.log("nav", "$id: signed out, so the account cannot name its channel") }
        val response = runCatching { client.nextAsAccount(id, token) }.getOrNull()
        val details = (response as? InnerTubeResponse.Success)?.body?.let { WatchNextParser.details(it, id) }
        val channelId = details?.channelId
        val url = channelId?.let(::youTubeChannelUrl) ?: return null.also {
            val said = response.toString().take(LOGGED_CHARS)
            Diag.log("nav", "$id: the account's watch page named no channel ($said)")
        }
        Diag.log("nav", "$id: the account's watch page names channel $channelId \"${details.author}\"")
        return MediaSource.VideoChannel(id = SourceId(url.value), title = details.author ?: channelId, channelUrl = url)
    }

    private companion object {
        const val LOGGED_CHARS = 300
    }
}
