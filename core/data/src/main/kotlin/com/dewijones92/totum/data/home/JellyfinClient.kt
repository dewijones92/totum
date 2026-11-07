package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.data.jellyfin.imagePath
import com.dewijones92.totum.data.jellyfin.streamPath
import com.dewijones92.totum.data.jellyfin.toPlayable
import com.dewijones92.totum.data.targets.TargetsJson
import com.dewijones92.totum.domain.PlayableItem
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

public interface JellyfinApi {
    public suspend fun browse(parent: String?, start: Int = 0): HomeAnswer<JellyfinListing>
    public fun streamUrl(item: JellyfinItem): HttpUrl
    public fun imageUrl(item: JellyfinItem): HttpUrl? = null
}

public fun JellyfinApi.playable(item: JellyfinItem): PlayableItem = item.toPlayable(streamUrl(item), imageUrl(item))

public class JellyfinClient(
    client: OkHttpClient,
    private val base: String,
    token: () -> String,
) : JellyfinApi {
    private val pi = HomePiCall(client, token, "jellyfin")

    override suspend fun browse(parent: String?, start: Int): HomeAnswer<JellyfinListing> {
        val query = listOfNotNull(
            parent?.let { "parent=${URLEncoder.encode(it, Charsets.UTF_8)}" },
            "start=$start".takeIf { start > 0 },
        ).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return pi.call("GET /jellyfin/browse$query", Request.Builder().url("$base/jellyfin/browse$query").get()) {
            TargetsJson.decodeFromString(JellyfinListing.serializer(), it)
        }
    }

    override fun streamUrl(item: JellyfinItem): HttpUrl = HttpUrl.of("$base/${item.streamPath()}")

    override fun imageUrl(item: JellyfinItem): HttpUrl = HttpUrl.of("$base/${item.imagePath()}")
}
