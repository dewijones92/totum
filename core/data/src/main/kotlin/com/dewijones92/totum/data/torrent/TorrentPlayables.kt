package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId

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
    public val SOURCE: SourceId = SourceId("torrent")

    /**
     * Every playable file, in broadcast order where that can be read.
     *
     * One item per file rather than one per torrent: a season pack IS two dozen things to watch,
     * and collapsing it to one would make the queue lie about what is in it.
     */
    public fun queueItems(server: HomeTorrentServer, torrent: PreparedTorrent): List<PlayableItem> =
        TorrentEpisodes.playableInOrder(torrent.files).map { file ->
            PlayableItem(
                item = MediaItem(
                    // The hash and file index together, so the same episode is the same item
                    // across restarts — which is what lets play-position and history work.
                    id = TorrentProgress.itemId(torrent.hash, file.index),
                    sourceId = SOURCE,
                    title = titleFor(torrent, file),
                    publishedAt = null,
                    duration = null,
                    author = torrent.name,
                    mediaUrl = server.stream(torrent, file),
                ),
                // The audio-only variant, so Listen mode costs 2.1 MB/min instead of 15.2.
                handle = PlayHandle.Podcast(audioUrl = server.audioStream(torrent, file)),
            )
        }

    /**
     * What a row says.
     *
     * A single-file torrent takes the torrent's name, because "feature.mkv" tells nobody what
     * they are about to watch. A pack takes the episode label, since the release name is already
     * on the group header above it and repeating it on every row is noise.
     */
    private fun titleFor(torrent: PreparedTorrent, file: TorrentFile): String {
        val playable = torrent.files.count { it.isPlayable }
        return if (playable <= 1) torrent.name else TorrentEpisodes.label(file)
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
