package com.dewijones92.totum.domain

public object TorrentIds {
    public const val PREFIX: String = "torrent:"
    public val SOURCE: SourceId = SourceId("torrent")
}

public val MediaItemId.isTorrent: Boolean get() = value.startsWith(TorrentIds.PREFIX)

public val MediaItem.isTorrent: Boolean get() = sourceId == TorrentIds.SOURCE || id.isTorrent
