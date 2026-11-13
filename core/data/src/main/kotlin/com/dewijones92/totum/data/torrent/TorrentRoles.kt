package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.Picture
import com.dewijones92.totum.domain.pictureOfFileName

public enum class TorrentFileRole { MAIN, EXTRA, SAMPLE }

public data class RoledFile(public val file: TorrentFile, public val role: TorrentFileRole)

public object TorrentRoles {

    public fun classify(torrent: PreparedTorrent): List<RoledFile> = classify(torrent.files).also { roled ->
        if (roled.any { it.role != TorrentFileRole.MAIN }) {
            Diag.log(
                "torrent",
                "${torrent.hash.take(HASH_CHARS)} \"${torrent.name}\" file roles: " +
                    roled.joinToString(" | ") { "#${it.file.index} ${it.role.name.lowercase()} (${it.file.sizeBytes}B)" },
            )
        }
    }

    public fun classify(files: List<TorrentFile>): List<RoledFile> {
        val playable = files.filter { it.isPlayable }
        val episodic = playable.any { TorrentEpisodes.episodeOf(it.name) != null }
        val videos = playable.filter { pictureOfFileName(it.name) != Picture.NO && !it.inFolder(SAMPLE_FOLDER) }
        val largest = videos.maxOfOrNull { it.sizeBytes } ?: 0L
        val roles = playable.associateWith { file ->
            when {
                file.inFolder(SAMPLE_FOLDER) -> TorrentFileRole.SAMPLE
                episodic -> TorrentFileRole.MAIN
                file.inFolder(EXTRA_FOLDER) -> TorrentFileRole.EXTRA
                file in videos && videos.size > 1 && file.sizeBytes < largest * SMALL_SHARE_PERCENT / PERCENT ->
                    TorrentFileRole.EXTRA
                else -> TorrentFileRole.MAIN
            }
        }
        val promoted = if (roles.values.any { it == TorrentFileRole.MAIN }) {
            null
        } else {
            playable.filter { roles[it] == TorrentFileRole.EXTRA }.maxByOrNull { it.sizeBytes }
        }
        return playable.map { RoledFile(it, if (it == promoted) TorrentFileRole.MAIN else roles.getValue(it)) }
    }

    private fun TorrentFile.inFolder(pattern: Regex): Boolean =
        path.split('/').dropLast(1).any { pattern.containsMatchIn(it.trim()) }

    private val SAMPLE_FOLDER = Regex("""(?i)^samples?$""")
    private val EXTRA_FOLDER = Regex(
        """(?i)^(extras?|bonus.*|featurettes?|behind the scenes|deleted scenes?|trailers?|interviews?|making of.*""" +
            """|special features?)$""",
    )
    private const val SMALL_SHARE_PERCENT = 15L
    private const val PERCENT = 100L
    private const val HASH_CHARS = 12
}

internal object TorrentNames {

    fun clean(name: String): String {
        val release = ReleaseName(name)
        val title = release.title
        if (title.isEmpty() || title.size == release.words.size) return name
        val year = release.tail.firstOrNull()?.takeIf { YEAR.matches(it) }
        return title.joinToString(" ") + (year?.let { " ($it)" } ?: "")
    }

    private val YEAR = Regex("""(19|20)\d\d""")
}
