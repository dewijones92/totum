package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.torrent.HomeTorrentServer
import com.dewijones92.totum.data.torrent.TorrentLink
import com.dewijones92.totum.data.torrent.TorrentPlayables
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.placeholderFor
import com.dewijones92.totum.settings.DownloadChoice
import com.dewijones92.totum.sharedWatchUrl

sealed interface Sent {
    data class Video(val title: String) : Sent
    data class Torrent(val name: String, val files: Int) : Sent
    data class Refused(val why: NotSent) : Sent
}

enum class NotSent(val words: String) {
    NO_HOME("no home server is set up"),
    NO_LINK("not a YouTube or magnet link"),
    NO_VIDEO_ID("no video id in the link"),
    NO_TORRENTS("no torrent server at home"),
    NOT_PREPARED("the torrent could not be prepared"),
    NOTHING_PLAYABLE("the torrent has nothing playable"),
}

class SendHome(
    private val home: HomeDownloads,
    private val torrents: () -> HomeTorrentServer?,
    private val describe: suspend (HttpUrl) -> MediaItem?,
    private val choice: () -> DownloadChoice?,
) {
    suspend fun send(text: String?): Sent {
        val shared = text.orEmpty()
        if (!home.available) return refused(NotSent.NO_HOME, shared)
        val magnet = MAGNET.find(shared)?.value
        return if (magnet != null) torrent(magnet) else video(shared)
    }

    private suspend fun video(shared: String): Sent {
        val url = sharedWatchUrl(shared) ?: return refused(NotSent.NO_LINK, shared)
        val item = describe(url) ?: placeholderFor(url, SOURCE) ?: return refused(NotSent.NO_VIDEO_ID, shared)
        val chosen = choice() ?: DownloadChoice.Video(null)
        Diag.log("home", "shared to send home: \"${item.title}\" as ${chosen.stored}")
        home.start(PlayableItem(item, PlayHandle.Video(url)), chosen)
        return Sent.Video(item.title)
    }

    private suspend fun torrent(magnet: String): Sent {
        val server = torrents() ?: return refused(NotSent.NO_TORRENTS, magnet)
        val prepared = server.prepare(TorrentLink.Magnet(magnet)) ?: return refused(NotSent.NOT_PREPARED, magnet)
        val files = TorrentPlayables.queueItems(server, prepared)
        if (files.isEmpty()) return refused(NotSent.NOTHING_PLAYABLE, magnet)
        Diag.log("home", "shared magnet to send home: \"${prepared.name}\", ${files.size} playable file(s)")
        files.forEach { home.start(it, null) }
        return Sent.Torrent(prepared.name, files.size)
    }

    private fun refused(why: NotSent, text: String): Sent {
        Diag.log("home", "shared text not sent home: ${why.words} [${text.take(LOGGED)}]")
        return Sent.Refused(why)
    }

    private companion object {
        val MAGNET = Regex("""magnet:\?\S+""")
        val SOURCE = SourceId("shared")
        const val LOGGED = 120
    }
}
