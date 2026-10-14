package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.actions.ActionResult
import com.dewijones92.totum.innertube.actions.VideoRating
import com.dewijones92.totum.innertube.actions.YouTubeActions
import com.dewijones92.totum.innertube.music.LibraryArtist
import com.dewijones92.totum.innertube.music.LibraryResult
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.YouTubeMusicLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class MusicFavourites(
    private val library: YouTubeMusicLibrary,
    private val actions: YouTubeActions,
    private val follows: Follows,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {

    interface Follows {
        val followedChannelIds: Flow<Set<String>?>

        suspend fun set(channelId: String, name: String, followed: Boolean): Boolean
    }

    data class Shelf(
        val loading: Boolean = true,
        val signedOut: Boolean = false,
        val failed: Boolean = false,
        val likedSongs: Int? = null,
        val likedKnown: Boolean = false,
        val albums: List<MusicAlbumRef> = emptyList(),
        val artists: List<LibraryArtist> = emptyList(),
    ) {
        val isEmpty: Boolean get() = !likedKnown && albums.isEmpty() && artists.isEmpty()
    }

    enum class Outcome(val phrase: String) {
        DONE("done, and the account says so"),
        NOT_CONFIRMED("YouTube accepted it but the account does not show it yet"),
        FAILED("YouTube refused it"),
        SIGNED_OUT("not signed in"),
        NOTHING_TO_SAVE("the album has no playlist id to save"),
    }

    private val _shelf = MutableStateFlow(Shelf())
    val shelf: StateFlow<Shelf> = _shelf.asStateFlow()

    private var asked = false

    suspend fun loadOnce() {
        if (asked) return
        asked = true
        refresh()
    }

    suspend fun refresh() {
        try {
            coroutineScope {
                asked = true
                _shelf.update { it.copy(loading = true, failed = false) }
                val liked = async { library.likedSongCount() }
                val albums = async { library.albums() }
                val artists = async { library.artists() }
                val results = listOf(liked.await(), albums.await(), artists.await())
                val signedOut = results.any { it is LibraryResult.SignedOut }
                val failures = results.filterIsInstance<LibraryResult.Failure>()
                _shelf.value = Shelf(
                    loading = false,
                    signedOut = signedOut,
                    failed = failures.isNotEmpty() && !signedOut,
                    likedSongs = (liked.await() as? LibraryResult.Success)?.value,
                    likedKnown = liked.await() is LibraryResult.Success,
                    albums = albums.await().orEmpty(),
                    artists = artists.await().orEmpty(),
                )
                Diag.log(
                    TAG,
                    "favourites read: signedOut=$signedOut likedSongs=${_shelf.value.likedSongs ?: "not said"} " +
                        "savedAlbums=${_shelf.value.albums.size} followedArtists=${_shelf.value.artists.size} " +
                        "failures=${failures.map { it.detail }}",
                )
            }
        } catch (cancelled: CancellationException) {
            asked = false
            _shelf.update { it.copy(loading = false) }
            Diag.log(TAG, "favourites read cancelled before it finished; the next page asks again")
            throw cancelled
        }
    }

    fun isSaved(albumBrowseId: String): Boolean = _shelf.value.albums.any { it.browseId == albumBrowseId }

    suspend fun setAlbumSaved(browseId: String, playlistId: String?, title: String, saved: Boolean): Outcome {
        val verb = if (saved) "save" else "remove"
        if (playlistId == null) {
            return Outcome.NOTHING_TO_SAVE.also { log("$verb album $browseId \"$title\"", it, "playlist=none") }
        }
        val written = actions.setPlaylistSaved(playlistId, saved)
        val (outcome, reads) = written.failureOutcome()?.let { it to 0 } ?: readBack(saved) {
            val read = library.albums().successValue()
            read?.let { list -> _shelf.update { it.copy(albums = list) } }
            read?.any { it.browseId == browseId }
        }
        log("$verb album $browseId \"$title\"", outcome, "playlist=$playlistId server=${written.phrase()} reads=$reads")
        return outcome
    }

    suspend fun songRating(videoId: String): VideoRating? = actions.rating(videoId).also {
        Diag.log(TAG, "song $videoId rating on the account: ${it ?: "unknown (signed out or not said)"}")
    }

    suspend fun setSongLiked(videoId: String, title: String, liked: Boolean): Outcome {
        val target = if (liked) VideoRating.LIKE else VideoRating.NONE
        val written = actions.setRating(videoId, target)
        val (outcome, reads) = written.failureOutcome()?.let { it to 0 } ?: readBack(liked) {
            actions.rating(videoId)?.let { it == VideoRating.LIKE }
        }
        if (outcome == Outcome.DONE) {
            library.likedSongCount().successValue()?.let { count -> _shelf.update { it.copy(likedSongs = count) } }
        }
        log(
            "${if (liked) "like" else "unlike"} song $videoId \"$title\"",
            outcome,
            "server=${written.phrase()} reads=$reads"
        )
        return outcome
    }

    fun followed(artistBrowseId: String): Flow<Boolean?> =
        follows.followedChannelIds.map { ids -> ids?.contains(artistBrowseId) }

    suspend fun setArtistFollowed(browseId: String, name: String, followed: Boolean): Outcome {
        val written = follows.set(browseId, name, followed)
        val (outcome, reads) = if (!written) {
            Outcome.FAILED to 0
        } else {
            readBack(followed) {
                val read = library.artists().successValue()
                read?.let { list -> _shelf.update { it.copy(artists = list) } }
                read?.any { it.name.equals(name, ignoreCase = true) }
            }
        }
        log(
            "${if (followed) "follow" else "unfollow"} artist $browseId \"$name\"",
            outcome,
            "server=$written reads=$reads"
        )
        return outcome
    }

    private suspend fun readBack(wanted: Boolean, present: suspend () -> Boolean?): Pair<Outcome, Int> {
        var reads = 0
        while (true) {
            reads++
            if (present() == wanted) return Outcome.DONE to reads
            if (reads >= READ_BACK_TRIES) return Outcome.NOT_CONFIRMED to reads
            pause(READ_BACK_PAUSE_MS)
        }
    }

    private fun log(what: String, outcome: Outcome, detail: String) {
        val line = "$what -> ${outcome.name}: ${outcome.phrase} [$detail]"
        if (outcome == Outcome.DONE) Diag.log(TAG, line) else Diag.warn(TAG, line)
    }

    private companion object {
        const val TAG = "favourites"
        const val READ_BACK_TRIES = 5
        const val READ_BACK_PAUSE_MS = 1_500L
    }
}

private fun ActionResult.failureOutcome(): MusicFavourites.Outcome? = when (this) {
    ActionResult.Success -> null
    ActionResult.SignedOut -> MusicFavourites.Outcome.SIGNED_OUT
    is ActionResult.Failure -> MusicFavourites.Outcome.FAILED
}

private fun ActionResult.phrase(): String = when (this) {
    ActionResult.Success -> "accepted"
    ActionResult.SignedOut -> "signed out"
    is ActionResult.Failure -> "refused ($detail)"
}

private fun <T> LibraryResult<List<T>>.orEmpty(): List<T> = successValue().orEmpty()

private fun <T> LibraryResult<T>.successValue(): T? = (this as? LibraryResult.Success)?.value
