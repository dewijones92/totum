package com.dewijones92.totum.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.innertube.actions.ActionResult
import com.dewijones92.totum.innertube.actions.VideoRating
import com.dewijones92.totum.innertube.actions.YouTubeActions
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.comments.Comment
import com.dewijones92.totum.innertube.comments.CommentsResult
import com.dewijones92.totum.innertube.comments.RepliesResult
import com.dewijones92.totum.innertube.comments.YouTubeComments
import com.dewijones92.totum.innertube.related.RelatedResult
import com.dewijones92.totum.innertube.related.YouTubeRelated
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.settings.PlaybackMode
import com.dewijones92.totum.ui.common.toMediaItem
import com.dewijones92.totum.video.VideoPlaybackLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the full player's watch page for one video: reads comments (public),
 * and — when signed in — likes and posts comments. Keyed by video id, so
 * switching videos resets. Writes are optimistic where it helps responsiveness
 * but always reflect the real result.
 */
// The watch page's one view-model: comments, related/autoplay, rating, Watch
// Later, Listen, quality and comment-posting are all thin actions that belong
// together; splitting to satisfy the counter would only scatter the page's logic.
// Seven collaborators because the watch screen genuinely coordinates that many surfaces —
// comments, related, actions, account, playback, the queue and preferences. Splitting it
// would scatter one screen's logic rather than simplify it.
@Suppress("TooManyFunctions")
class WatchViewModel
@Suppress("LongParameterList")
constructor(
    private val commentsSource: YouTubeComments,
    private val relatedSource: YouTubeRelated,
    private val actions: YouTubeActions,
    private val account: YouTubeAccount,
    private val launcher: VideoPlaybackLauncher,
    private val queue: PlaybackQueue,
    private val preferences: AppPreferences,
) : ViewModel() {

    /** The current video's selectable qualities; switching replays from the same spot. */
    val quality: StateFlow<VideoPlaybackLauncher.QualityState> = launcher.quality

    fun selectQuality(id: String): Unit = launcher.selectQuality(id)

    /** Switches the current video's audio track — see [VideoPlaybackLauncher.selectAudioTrack]. */
    fun selectAudioTrack(languageCode: String) {
        viewModelScope.launch { launcher.selectAudioTrack(languageCode) }
    }

    /** Switch the current video to audio-only ("Listen"). */
    /**
     * Switches to audio and makes that the mode, so the next item plays the same way —
     * wanting audio is situational, not per-video.
     */
    fun listen() {
        preferences.setPlaybackMode(PlaybackMode.AUDIO)
        launcher.listen()
    }

    /** Leave audio-only and return to watching the video ("Watch"). */
    /** Switches to video and makes that the mode. */
    fun watch() {
        preferences.setPlaybackMode(PlaybackMode.VIDEO)
        launcher.watch()
    }

    /**
     * Watches **this item only**, leaving the mode alone — for the unambiguous
     * "I want to see this" signals (Shorts, an explicit fullscreen tap).
     */
    fun watchOnce(): Unit = launcher.watch()

    sealed interface CommentsState {
        data object Loading : CommentsState
        data class Loaded(val comments: List<Comment>) : CommentsState
        data object Disabled : CommentsState
        data object Error : CommentsState
    }

    enum class PostState { Idle, Posting, Posted, Failed }

    sealed interface RelatedState {
        data object Loading : RelatedState
        data class Loaded(val videos: List<MediaItem>) : RelatedState
        data object Error : RelatedState
    }

    /** A comment thread's replies, once expanded. Absent from the map = collapsed. */
    sealed interface RepliesState {
        data object Loading : RepliesState

        /** [moreToken] is set when the thread has further reply pages. */
        data class Loaded(val replies: List<Comment>, val moreToken: String?) : RepliesState
        data object Error : RepliesState
    }

    private val _comments = MutableStateFlow<CommentsState>(CommentsState.Loading)
    val comments: StateFlow<CommentsState> get() = _comments.asStateFlow()

    private val _related = MutableStateFlow<RelatedState>(RelatedState.Loading)
    val related: StateFlow<RelatedState> get() = _related.asStateFlow()

    // Expanded comment threads, keyed by the parent comment id. Absent = collapsed.
    private val _replies = MutableStateFlow<Map<String, RepliesState>>(emptyMap())
    val replies: StateFlow<Map<String, RepliesState>> get() = _replies.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _rating = MutableStateFlow(VideoRating.NONE)
    val rating: StateFlow<VideoRating> = _rating.asStateFlow()

    private val _inWatchLater = MutableStateFlow(false)
    val inWatchLater: StateFlow<Boolean> = _inWatchLater.asStateFlow()

    private val _postState = MutableStateFlow(PostState.Idle)
    val postState: StateFlow<PostState> = _postState.asStateFlow()

    private var videoId: String? = null

    fun bind(videoId: String) {
        if (videoId == this.videoId) return
        this.videoId = videoId
        _comments.value = CommentsState.Loading
        _replies.value = emptyMap()
        _related.value = RelatedState.Loading
        _rating.value = VideoRating.NONE
        _inWatchLater.value = false
        _postState.value = PostState.Idle
        viewModelScope.launch { _signedIn.value = account.isSignedIn() }
        viewModelScope.launch {
            _comments.value = when (val result = commentsSource.forVideo(videoId)) {
                is CommentsResult.Success -> CommentsState.Loaded(result.comments)
                CommentsResult.Disabled -> CommentsState.Disabled
                is CommentsResult.Failure -> CommentsState.Error
            }
        }
        viewModelScope.launch {
            _related.value = when (val result = relatedSource.relatedTo(videoId)) {
                // Converted here, at the boundary, so the list downstream is the domain
                // shape every other list uses — and therefore gets the shared row.
                is RelatedResult.Success ->
                    RelatedState.Loaded(result.videos.map { it.toMediaItem(RELATED_SOURCE) })
                is RelatedResult.Failure -> RelatedState.Error
            }
        }
    }

    /** Plays a tapped related video through the queue, like every other tap. */
    fun playRelated(video: MediaItem) {
        val playable = video.toPlayableOrNull() ?: return
        viewModelScope.launch { queue.playNow(playable) }
    }

    /**
     * Plays the next up-next video when the current one ends — the top related
     * that isn't the video just watched. No-op if related hasn't loaded or is
     * empty.
     */
    fun autoplayNext() {
        val videos = (_related.value as? RelatedState.Loaded)?.videos ?: return
        val next = videos.firstOrNull { it.id.value != videoId } ?: return
        playRelated(next)
    }

    /** Toggles like on/off (YouTube clears any dislike when you like). */
    fun toggleLike() {
        setRating(if (_rating.value == VideoRating.LIKE) VideoRating.NONE else VideoRating.LIKE)
    }

    /** Toggles dislike on/off (YouTube clears any like when you dislike). */
    fun toggleDislike() {
        setRating(if (_rating.value == VideoRating.DISLIKE) VideoRating.NONE else VideoRating.DISLIKE)
    }

    private fun setRating(target: VideoRating) {
        val id = videoId ?: return
        val previous = _rating.value
        _rating.value = target // optimistic
        viewModelScope.launch {
            if (actions.setRating(id, target) !is ActionResult.Success) _rating.value = previous
        }
    }

    /** Adds/removes the current video to/from Watch Later (optimistic). */
    fun toggleWatchLater() {
        val id = videoId ?: return
        val target = !_inWatchLater.value
        _inWatchLater.value = target
        viewModelScope.launch {
            if (actions.setSavedToWatchLater(id, target) !is ActionResult.Success) _inWatchLater.value = !target
        }
    }

    fun postComment(text: String) {
        val id = videoId ?: return
        if (text.isBlank() || _postState.value == PostState.Posting) return
        _postState.value = PostState.Posting
        viewModelScope.launch {
            _postState.value = if (actions.postComment(id, text.trim()) is ActionResult.Success) {
                PostState.Posted
            } else {
                PostState.Failed
            }
        }
    }

    /** Expands a comment's replies (loading them on first open) or collapses them. */
    fun toggleReplies(comment: Comment) {
        val token = comment.replyToken ?: return
        if (_replies.value.containsKey(comment.id)) {
            _replies.update { it - comment.id }
            return
        }
        _replies.update { it + (comment.id to RepliesState.Loading) }
        viewModelScope.launch {
            val state = when (val result = commentsSource.repliesFor(token)) {
                is RepliesResult.Success -> RepliesState.Loaded(result.replies, result.continuation)
                is RepliesResult.Failure -> RepliesState.Error
            }
            _replies.update { it + (comment.id to state) }
        }
    }

    /** Appends the next page of a long thread's replies ("show more replies"). */
    fun loadMoreReplies(commentId: String) {
        val loaded = _replies.value[commentId] as? RepliesState.Loaded ?: return
        val token = loaded.moreToken ?: return
        viewModelScope.launch {
            val result = commentsSource.repliesFor(token) as? RepliesResult.Success ?: return@launch
            _replies.update {
                it + (commentId to RepliesState.Loaded(loaded.replies + result.replies, result.continuation))
            }
        }
    }

    fun clearPostState() = _postState.update { PostState.Idle }

    companion object {
        /** Ad-hoc source for a related video — not tied to a subscribed channel or feed. */
        private val RELATED_SOURCE = SourceId("watch:related-video")

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WatchViewModel(
                    container.youTubeComments,
                    container.youTubeRelated,
                    container.youTubeActions,
                    container.youTubeAccount,
                    container.videoPlaybackLauncher,
                    container.playbackQueue,
                    container.appPreferences,
                )
            }
        }
    }
}
