package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.canonicalWatchUrl
import com.dewijones92.totum.common.youTubeVideoId

public sealed interface ShareLink {
    public val why: String

    public data class Link(val url: HttpUrl, override val why: String) : ShareLink

    public data class TitleOnly(override val why: String) : ShareLink
}

public fun MediaItem.shareLink(homeHost: String?): ShareLink {
    if (isTorrent) return ShareLink.TitleOnly("a torrent has no public address, only the Pi's stream")
    val watch = listOfNotNull(mediaUrl, HttpUrl.parse(id.value)).firstOrNull { it.youTubeVideoId() != null }
    if (watch != null) return ShareLink.Link(watch.canonicalWatchUrl(), "a YouTube video, as its watch address")
    val media = mediaUrl ?: return ShareLink.TitleOnly("no address is known for it")
    if (homeHost != null && media.host.equals(homeHost, ignoreCase = true)) {
        return ShareLink.TitleOnly("its address is the Pi's, which only this phone is signed in to")
    }
    return ShareLink.Link(media, "its own media address")
}

public fun ShareLink.messageFor(title: String): String = when (this) {
    is ShareLink.Link -> "$title\n${url.value}"
    is ShareLink.TitleOnly -> title
}
