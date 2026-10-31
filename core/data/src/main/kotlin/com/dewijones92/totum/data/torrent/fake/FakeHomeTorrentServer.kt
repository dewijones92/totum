package com.dewijones92.totum.data.torrent.fake

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.torrent.HomeTorrentServer
import com.dewijones92.totum.data.torrent.PreparedTorrent
import com.dewijones92.totum.data.torrent.TorrentCategory
import com.dewijones92.totum.data.torrent.TorrentFile
import com.dewijones92.totum.data.torrent.TorrentLink
import com.dewijones92.totum.data.torrent.TorrentResult
import com.dewijones92.totum.data.torrent.TorrentSearchResult

/** In-memory [HomeTorrentServer] for tests and previews — no Pi, no swarm, no network. */
public class FakeHomeTorrentServer(
    public var results: List<TorrentResult> = emptyList(),
    public var failure: String? = null,
    public var slow: Boolean = false,
) : HomeTorrentServer {

    /** Links handed to [prepare], so a test can assert the app added what the user picked. */
    public val prepared: MutableList<TorrentLink> = mutableListOf()

    public var files: List<TorrentFile> = listOf(
        TorrentFile(index = 1, path = "Some.Release/feature.mkv", sizeBytes = 1_700_000_000),
    )

    public val searchedCategories: MutableList<TorrentCategory> = mutableListOf()

    override suspend fun search(query: String, category: TorrentCategory): TorrentSearchResult {
        searchedCategories += category
        return failure?.let { TorrentSearchResult.Failure(it, slow) } ?: TorrentSearchResult.Success(results)
    }

    override suspend fun prepare(link: TorrentLink): PreparedTorrent? {
        prepared += link
        return failure?.let { null } ?: PreparedTorrent("abc123", "Some.Release", files)
    }

    override fun stream(torrent: PreparedTorrent, file: TorrentFile): HttpUrl =
        HttpUrl.of(
            "https://home.test/stream/${file.name.replace(" ", "%20")}?link=${torrent.hash}&index=${file.index}&play"
        )

    /** A plausible audio URL so tests can assert one was offered without a server. */
    override fun audioStream(torrent: PreparedTorrent, file: TorrentFile): HttpUrl =
        HttpUrl.of("https://home.test/ts/audio/${torrent.hash}/${file.index}/index.m3u8")

    /** Records that warming was asked for; there is nothing to warm in memory. */
    public var warmed: MutableList<String> = mutableListOf()

    public val warmedVideo: MutableList<Pair<String, Int>> = mutableListOf()

    override suspend fun warmVideo(torrent: PreparedTorrent, file: TorrentFile) {
        warmedVideo += torrent.hash to file.index
    }

    public val warmedStreams: MutableList<String> = mutableListOf()

    override suspend fun warmStream(streamUrl: HttpUrl) {
        warmedStreams += streamUrl.value
    }

    override suspend fun warmAudio(audioUrl: HttpUrl) {
        warmed += audioUrl.value.substringBefore('?').removeSuffix("/index.m3u8").substringAfterLast("/audio/")
    }
}
