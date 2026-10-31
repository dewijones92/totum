package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.NaturalOrder
import com.dewijones92.totum.domain.Picture
import com.dewijones92.totum.domain.pictureOfFileName

/**
 * Turns the files inside a torrent into something orderable and readable.
 *
 * A season pack is a folder of two dozen files with names like
 * `Show.Name.S01E03.1080p.BluRay.x264-GROUP.mkv`. Left alone they queue in whatever order the
 * torrent lists them — usually right, occasionally not — and every row in the queue reads like a
 * release string rather than an episode.
 *
 * So the season and episode numbers are parsed out. Deliberately conservative: this is a small
 * fragile thing sitting in front of a feature that must not break, so anything it cannot parse
 * confidently keeps its filename and its original position. A wrong episode label is worse than
 * no episode label, because the wrong one is believed.
 */
public object TorrentEpisodes {

    /**
     * Playable files, ordered for queueing: by season and episode where known, all-audio files (a
     * book's chapters) by the numbers in their names, else as listed.
     *
     * Stable within each group, so a pack that parses cleanly is in broadcast order and one that
     * does not is exactly as the torrent had it — never scrambled by a half-successful parse.
     */
    public fun playableInOrder(files: List<TorrentFile>): List<TorrentFile> {
        val playable = files.filter { it.isPlayable }
        val parsed = playable.map { it to episodeOf(it.name) }
        return if (parsed.any { it.second != null }) {
            // Unparsed files sort last rather than first: they are usually extras, and putting a
            // "behind the scenes" ahead of episode one would be a strange thing to queue.
            parsed.sortedWith(
                compareBy(
                    { it.second == null },
                    { it.second?.season ?: 0 },
                    { it.second?.episode ?: 0 },
                ),
            ).map { it.first }
        } else if (playable.all { pictureOfFileName(it.name) == Picture.NO }) {
            playable.sortedWith(compareBy(NaturalOrder) { it.path })
        } else {
            playable
        }
    }

    /**
     * A readable label: `S01E03` when known, else the filename.
     *
     * The fallback is the point. A file this cannot read still gets a name a person can match
     * against what they are looking at, rather than a blank or a guess.
     */
    public fun label(file: TorrentFile): String =
        episodeOf(file.name)?.let { "S%02dE%02d".format(it.season, it.episode) }
            ?: file.name.substringBeforeLast('.')

    /**
     * Season and episode, or null when nothing is confidently readable.
     *
     * Handles the two forms that actually appear in release names — `S01E03` and `1x03` — and
     * nothing else on purpose. A bare `103` or `Episode 3` is ambiguous with resolutions, years
     * and group numbers, and guessing at those is how a film ends up labelled as season 10.
     */
    public fun episodeOf(name: String): Episode? {
        SEASON_EPISODE.find(name)?.let { match ->
            return Episode(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
        CROSS_FORM.find(name)?.let { match ->
            return Episode(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
        return null
    }

    public data class Episode(public val season: Int, public val episode: Int)

    /** `S01E03`, the overwhelmingly common form; case-insensitive because releases vary. */
    private val SEASON_EPISODE = Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""")

    /** `1x03`, the older form. Bounded so a resolution like `1920x1080` cannot match. */
    private val CROSS_FORM = Regex("""(?<!\d)(\d{1,2})[xX](\d{2})(?!\d)""")
}
