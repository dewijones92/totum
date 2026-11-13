package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.Picture
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.TorrentIds
import com.dewijones92.totum.domain.pictureOfFileName

/**
 * Turns a prepared torrent into ordinary queue items.
 *
 * This is where the whole feature becomes unified, and it is deliberately tiny. A season pack
 * comes out as a list of [PlayableItem]s exactly like a podcast feed's episodes or a channel's
 * videos, so "queue this season" is the SAME operation as "play all of this playlist" —
 * `PlaybackQueue.playAll(items, group)`, which already renders a group header and can remove the
 * group as a unit. Nothing above this function knows a torrent was involved.
 *
 * The handle is [PlayHandle.Podcast], which reads oddly and is right: that handle means "play
 * this URL directly", which is exactly what a TorrServer stream is. The alternative would be a
 * fourth `PlayHandle` whose routing, download strategy and playback path were identical to an
 * existing one — a distinction with no behaviour behind it, and the sort of thing the project's
 * first law exists to prevent.
 */
public object TorrentPlayables {

    /** The source id every torrent item shares, so they group and filter as one kind. */
    public val SOURCE: SourceId = TorrentIds.SOURCE

    /**
     * Every playable file, in broadcast order where that can be read.
     *
     * One item per file rather than one per torrent: a season pack IS two dozen things to watch,
     * and collapsing it to one would make the queue lie about what is in it.
     */
    public suspend fun queueItems(
        server: HomeTorrentServer,
        torrent: PreparedTorrent,
        kind: MediaContentKind = MediaContentKind.STANDARD,
        artwork: HttpUrl? = null,
    ): List<PlayableItem> {
        val picture = artworkFor(server, torrent, artwork)
        val roles = TorrentRoles.classify(torrent).filter { it.role != TorrentFileRole.SAMPLE }
            .associate { it.file.index to it.role }
        return TorrentEpisodes.playableInOrder(torrent.files).map { file ->
            PlayableItem(
                item = MediaItem(
                    // The hash and file index together, so the same episode is the same item
                    // across restarts — which is what lets play-position and history work.
                    id = TorrentProgress.itemId(torrent.hash, file.index),
                    sourceId = SOURCE,
                    title = titleFor(torrent, file, roles),
                    publishedAt = null,
                    duration = null,
                    author = torrent.name,
                    mediaUrl = server.stream(torrent, file),
                    contentKind = kind,
                    thumbnailUrl = picture,
                ),
                // The audio-only variant, so Listen mode costs 2.1 MB/min instead of 15.2.
                handle = PlayHandle.Podcast(
                    audioUrl = server.audioStream(torrent, file).takeIf { pictureOfFileName(file.name) != Picture.NO },
                ),
            )
        }
    }

    private suspend fun artworkFor(server: HomeTorrentServer, torrent: PreparedTorrent, given: HttpUrl?): HttpUrl? {
        if (given != null) {
            Diag.log("torrent", "\"${torrent.name}\" artwork: the caller's, $given")
            return given
        }
        val found = server.posterFor(torrent.name)
        Diag.log(
            "torrent",
            "\"${torrent.name}\" artwork: " +
                (found?.let { "looked up by title and year, $it" } ?: "none (no poster from its site, none found by title)"),
        )
        return found
    }

    /**
     * What a row says.
     *
     * A single-file torrent takes the torrent's name, because "feature.mkv" tells nobody what
     * they are about to watch. A pack takes the episode label, since the release name is already
     * on the group header above it and repeating it on every row is noise.
     */
    private fun titleFor(torrent: PreparedTorrent, file: TorrentFile, roles: Map<Int, TorrentFileRole>): String {
        val playable = roles.size
        if (playable <= 1) return torrent.name
        val mains = roles.values.count { it == TorrentFileRole.MAIN }
        return if (mains == 1 && roles[file.index] == TorrentFileRole.MAIN) {
            TorrentNames.clean(torrent.name)
        } else {
            TorrentEpisodes.label(file)
        }
    }

    public fun details(torrent: PreparedTorrent): Map<MediaItemId, TorrentFileDetail> =
        TorrentRoles.classify(torrent).associate {
            TorrentProgress.itemId(torrent.hash, it.file.index) to TorrentFileDetail(it.file.sizeBytes, it.role)
        }
}

/**
 * Whether the queue's automatic downloads — which exist to fetch AUDIO — can fetch this at all.
 *
 * False for a torrent. The home server's audio-only form is a live HLS playlist, which a plain
 * download cannot store as one file, so an `audioOnly = true` request quietly fetched the whole film
 * instead: proven on a device 2026-08-06, where a torrent requested audio-only was recorded
 * `copy=full`. A queue of films would fill the phone several times over to deliver something nobody
 * asked for, so nothing fetches them on your behalf and a deliberate tap still does.
 *
 * Here rather than in three call sites — the downloader, the queue banner and the diagnostics
 * report — because a banner promising a fetch the downloader will never make is worse than no
 * banner, and that is exactly what two copies of this rule would produce.
 */
public val PlayableItem.hasAudioOnlyFetch: Boolean
    get() = item.sourceId != TorrentPlayables.SOURCE

public data class TorrentFileDetail(public val sizeBytes: Long, public val role: TorrentFileRole)
