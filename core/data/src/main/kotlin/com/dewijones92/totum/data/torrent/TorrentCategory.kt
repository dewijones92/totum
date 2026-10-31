package com.dewijones92.totum.data.torrent

public enum class TorrentCategory(internal val newznab: Int?, internal val apibay: Int?) {
    ANY(null, null),
    AUDIOBOOKS(newznab = 3030, apibay = 102),
}
