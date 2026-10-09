package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.feeds.parseClockToSeconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object MusicPageParser {

    fun album(body: String, browseId: String): MusicAlbum? {
        val root = parseMusicRoot(body) ?: return null
        val header = root.firstObject("musicResponsiveHeaderRenderer") ?: return null
        val title = header.obj("title").text() ?: return null
        val art = header.obj("thumbnail")?.bestThumbnailUrl()
        val subtitle = header.obj("subtitle").text().orEmpty().segments()
        val strapline = header.obj("straplineTextOne")
        val artistId = strapline?.firstBrowseLink()?.takeIf { it.pageType == ARTIST_PAGE }?.browseId
        val tracks = LinkedHashMap<String, MusicSong>()
        root.collectEach("musicShelfRenderer") { shelf ->
            shelf.collectEach(ROW) { row ->
                row.toSongOrNull(fallbackArt = art)?.let { song ->
                    tracks.putIfAbsent(
                        song.videoId,
                        song.copy(
                            album = song.album ?: title,
                            albumId = browseId,
                            artistId = song.artistId ?: artistId
                        ),
                    )
                }
            }
        }
        return MusicAlbum(
            browseId = browseId,
            title = title,
            artist = strapline.text(),
            artistBrowseId = artistId,
            year = subtitle.lastOrNull { it.isYear() },
            kind = releaseKindOf(subtitle.firstOrNull()),
            summary = header.obj("secondSubtitle").text(),
            description = header.firstObject("musicDescriptionShelfRenderer")?.obj("description").text(),
            thumbnailUrl = art,
            playlistId = root.playlistIdOfAlbum(),
            tracks = tracks.values.toList(),
        )
    }

    fun albumBrowseIdIn(body: String): String? {
        val root = parseMusicRoot(body) ?: return null
        var found: String? = null
        root.collectEach("browseEndpoint") { endpoint ->
            val id = endpoint.str("browseId")
            if (found == null && id != null && id.startsWith(ALBUM_PREFIX)) found = id
        }
        return found
    }

    fun artist(body: String, browseId: String): MusicArtist? {
        val root = parseMusicRoot(body) ?: return null
        val header = root.firstObject("musicImmersiveHeaderRenderer")
            ?: root.firstObject("musicVisualHeaderRenderer")
            ?: return null
        val name = header.obj("title").text() ?: return null
        val topShelf = root.firstObject("musicShelfRenderer")
        val topSongs = LinkedHashMap<String, MusicSong>()
        topShelf?.collectEach(ROW) { row -> row.toSongOrNull()?.let { topSongs.putIfAbsent(it.videoId, it) } }
        val albums = mutableListOf<MusicAlbumRef>()
        val singles = mutableListOf<MusicAlbumRef>()
        val similar = mutableListOf<MusicArtistRef>()
        root.collectEach("musicCarouselShelfRenderer") { carousel ->
            carousel.collectEach(TWO_ROW) { item ->
                val link = item.obj("navigationEndpoint")?.firstBrowseLink() ?: return@collectEach
                when (link.pageType) {
                    ALBUM_PAGE -> item.toAlbumRef(link.browseId, artist = name)?.let { ref ->
                        if (ref.kind == MusicReleaseKind.ALBUM) albums += ref else singles += ref
                    }
                    ARTIST_PAGE -> item.toArtistRef(link.browseId)?.let { similar += it }
                    else -> Unit
                }
            }
        }
        return MusicArtist(
            browseId = browseId,
            name = name,
            description = header.obj("description").text(),
            thumbnailUrl = header.obj("thumbnail")?.bestThumbnailUrl(),
            topSongs = topSongs.values.toList(),
            allSongsBrowseId = topShelf?.obj("bottomEndpoint")?.firstBrowseLink()?.browseId,
            albums = albums.distinctBy { it.browseId },
            singles = singles.distinctBy { it.browseId },
            similar = similar.distinctBy { it.browseId },
            radio = header.obj("startRadioButton")?.seed(),
            shuffle = header.obj("playButton")?.seed(),
        )
    }

    fun radio(body: String): RadioBatch {
        val root = parseMusicRoot(body) ?: return RadioBatch(emptyList(), null)
        val songs = LinkedHashMap<String, MusicSong>()
        root.collectEach("playlistPanelVideoRenderer") { panel ->
            panel.toRadioSongOrNull()?.let { songs.putIfAbsent(it.videoId, it) }
        }
        val next = root.firstObject("nextRadioContinuationData")?.str("continuation")
            ?: root.firstObject("nextContinuationData")?.str("continuation")
        return RadioBatch(songs.values.toList(), next)
    }

    private fun JsonElement.playlistIdOfAlbum(): String? {
        var found: String? = null
        collectEach("watchEndpoint") { endpoint ->
            val id = endpoint.str("playlistId")
            if (found == null && id != null && id.startsWith(ALBUM_PLAYLIST_PREFIX)) found = id
        }
        if (found == null) {
            collectEach("watchPlaylistEndpoint") { endpoint ->
                val id = endpoint.str("playlistId")
                if (found == null && id != null && id.startsWith(ALBUM_PLAYLIST_PREFIX)) found = id
            }
        }
        return found
    }

    private fun JsonObject.seed(): RadioSeed? {
        var seed: RadioSeed? = null
        collectEach("watchEndpoint") { endpoint ->
            val playlist = endpoint.str("playlistId")
            if (seed == null && playlist != null) seed = RadioSeed(endpoint.str("videoId"), playlist)
        }
        if (seed == null) {
            collectEach("watchPlaylistEndpoint") { endpoint ->
                endpoint.str("playlistId")?.let { if (seed == null) seed = RadioSeed(null, it) }
            }
        }
        return seed
    }

    private fun JsonObject.toAlbumRef(browseId: String, artist: String?): MusicAlbumRef? {
        val title = obj("title").text() ?: return null
        val details = obj("subtitle").text().orEmpty().segments()
        return MusicAlbumRef(
            browseId = browseId,
            title = title,
            artist = details.filter { it.isCredit() }.joinToString(", ").ifBlank { null } ?: artist,
            year = details.lastOrNull { it.isYear() },
            kind = releaseKindOf(details.firstOrNull { !it.isYear() }),
            thumbnailUrl = obj("thumbnailRenderer")?.bestThumbnailUrl(),
        )
    }

    private fun JsonObject.toArtistRef(browseId: String): MusicArtistRef? = MusicArtistRef(
        browseId = browseId,
        name = obj("title").text() ?: return null,
        subtitle = obj("subtitle").text(),
        thumbnailUrl = obj("thumbnailRenderer")?.bestThumbnailUrl(),
    )

    private fun JsonObject.toRadioSongOrNull(): MusicSong? {
        val videoId = str("videoId") ?: return null
        val watchUrl = FeedVideo.watchUrlFor(videoId) ?: return null
        val byline = obj("longBylineText").text().orEmpty().segments().filter { it.isCredit() }
        val links = obj("longBylineText").linkedRuns()
        return MusicSong(
            videoId = videoId,
            title = obj("title").text() ?: return null,
            artist = byline.firstOrNull() ?: obj("shortBylineText").text(),
            album = byline.drop(1).firstOrNull(),
            durationSeconds = obj("lengthText").text()?.takeIf { it.isClock() }
                ?.let(::parseClockToSeconds),
            thumbnailUrl = obj("thumbnail")?.bestThumbnailUrl(),
            watchUrl = watchUrl,
            artistId = links.idOf(ARTIST_ID_PREFIX),
            albumId = links.idOf(ALBUM_ID_PREFIX),
        )
    }

    private const val ROW = "musicResponsiveListItemRenderer"
    private const val TWO_ROW = "musicTwoRowItemRenderer"
    private const val ALBUM_PREFIX = "MPREb_"
    private const val ALBUM_PLAYLIST_PREFIX = "OLAK5uy_"
}
