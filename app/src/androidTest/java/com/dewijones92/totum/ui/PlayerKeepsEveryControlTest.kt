package com.dewijones92.totum.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.domain.Chapter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.SleepTimerState
import com.dewijones92.totum.playback.VolumeBoost
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.player.CommentReplies
import com.dewijones92.totum.ui.player.FullPlayerOverlay
import com.dewijones92.totum.ui.player.PlaybackToggles
import com.dewijones92.totum.ui.player.QualityControl
import com.dewijones92.totum.ui.player.QueueControls
import com.dewijones92.totum.ui.player.WatchActions
import com.dewijones92.totum.ui.player.WatchViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every control the player screen offers, still offered.
 *
 * Dewi, 2026-08-07: *"redesign the player screen to be more sexy?????? ... but i dont wanna loose
 * any functionality"*. This is the second half of that sentence, made mechanical.
 *
 * Written and passing **against the screen as it was**, before any redesign, and then kept passing
 * through it. That order is the whole value: a checklist written afterwards only records what
 * survived, and would have quietly blessed anything dropped along the way. The screen has around
 * seventeen separate controls spread over eighteen files, which is far more than anyone can hold in
 * their head while moving things around.
 *
 * It asserts a control is REACHABLE, not where it is — the redesign is allowed to move anything,
 * group it, or put it behind a sheet. It is not allowed to lose it.
 */
@RunWith(AndroidJUnit4::class)
class PlayerKeepsEveryControlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val video = PlaybackState(
        itemId = MediaItemId("abc123"),
        title = "Is this Gary Stevensons last EVER interview?",
        artist = "Novara Media",
        artworkUrl = null,
        viewsText = "1.2M views",
        publishedText = "2 days ago",
        publishedAt = Instant.parse("2026-08-05T09:00:00Z"),
        kind = MediaKind.VIDEO,
        description = "Aaron Bastani speaks to Gary Stevenson about 3:21 the wealth gap.",
        isPlaying = true,
        positionMs = 60_000,
        durationMs = 2_520_000,
        speed = 1.5f,
        hasVideo = true,
        chapters = listOf(Chapter(0.seconds, "Intro"), Chapter(10.minutes, "The wealth gap")),
        volumeBoost = VolumeBoost.AUTO,
        skipSilence = true,
    )

    private fun queued(id: String, title: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = title,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private fun show(state: PlaybackState, quality: QualityControl = QualityControl.None.copy(canListen = true)) {
        composeTestRule.setContent {
            TotumTheme {
                FullPlayerOverlay(
                    state = state,
                    player = null,
                    comments = WatchViewModel.CommentsState.Loaded(emptyList()),
                    replies = CommentReplies.None,
                    related = WatchViewModel.RelatedState.Loaded(emptyList()),
                    // canAct, so like / dislike / watch-later are all on screen to be checked.
                    watchActions = WatchActions.ReadOnly.copy(canAct = true),
                    quality = quality,
                    sleepTimer = SleepTimerState.Off,
                    onDismiss = {},
                    onPlayRelated = {},
                    onStartSleep = {},
                    onStopSleepAfterItem = {},
                    onCancelSleep = {},
                    onTogglePlayPause = {},
                    onSeekTo = {},
                    onSeekBackward = {},
                    onSeekForward = {},
                    onSetSpeed = {},
                    onSetSubtitleLanguage = {},
                    onMore = {},
                    toggles = PlaybackToggles(),
                    queue = QueueControls(
                        upNext = listOf(QueueEntry(queued("next-1", "What Happened In Ceuta"))),
                        onPlay = {},
                        onRemove = {},
                    ),
                )
            }
        }
    }

    /**
     * Reachable by text OR content description, and scrolled to if need be.
     *
     * Deliberately loose about HOW it is found: by text or by content description, and scrolled to
     * if need be. Pinning the current spelling or position would make this a test of one design
     * rather than of the functionality, and the whole point is that the design is free to move.
     *
     * If a future redesign puts something behind a sheet or a menu, this has to learn to open it —
     * "reachable in one tap" is still reachable, but it has to be checked rather than assumed.
     */
    private fun assertReachable(what: String, vararg labels: String) {
        assertTrue(
            "$what is no longer reachable on the player (looked for ${labels.toList()}). " +
                "On screen now: ${onScreenLabels()}",
            labels.anyOnScreen(),
        )
    }

    private fun assertActionable(what: String, vararg labelRes: Int) {
        val labels = labelRes.map { InstrumentationRegistry.getInstrumentation().targetContext.getString(it) }
        assertTrue(
            "$what can no longer be pressed on the player (looked for exactly $labels). " +
                "On screen now: ${onScreenLabels()}",
            labels.any { label ->
                val matcher = hasText(label)
                    .or(hasContentDescription(label))
                    .and(hasClickAction())
                composeTestRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            },
        )
    }

    private fun Array<out String>.anyOnScreen(): Boolean = any { label ->
        val matcher = hasText(label, substring = true, ignoreCase = true)
            .or(hasContentDescription(label, substring = true, ignoreCase = true))
        val nodes = composeTestRule.onAllNodes(matcher).fetchSemanticsNodes()
        if (nodes.isEmpty()) return@any false
        runCatching { composeTestRule.onAllNodes(matcher)[0].performScrollTo() }
        true
    }

    /**
     * Everything currently labelled on screen, for a failure that can be acted on.
     *
     * "X is not reachable" alone cannot say whether the control moved, was renamed, or the sheet
     * never opened — three different problems, and guessing between them cost two runs.
     */
    private fun onScreenLabels(): String =
        runCatching { composeTestRule.onRoot(useUnmergedTree = true).printToString(maxDepth = TREE_DEPTH) }
            .getOrElse { "could not read the tree: $it" }

    @Test
    fun `the video player still offers everything it did`() {
        show(video)

        assertReachable("the title", "Gary Stevensons")
        assertReachable("the channel", "Novara Media")
        assertReachable("views and date", "1.2M views")
        assertReachable("the overflow menu", "More", "menu")
        assertReachable("like", "Like")
        assertReachable("dislike", "Dislike")
        assertReachable("watch later", "Watch later", "Save")
        assertReachable("the description", "Aaron Bastani")
        assertReachable("chapters", "The wealth gap", "Chapters")
        assertReachable("the up-next queue", "Up next", "Ceuta")
        assertActionable("the sleep timer", R.string.sleep_timer)
        assertActionable("skip silence", R.string.skip_silence)
        assertActionable("auto-play next", R.string.auto_play_next)
        assertActionable("fast start", R.string.sabr_playback)
        assertActionable("volume boost", R.string.volume_boost)
        assertActionable("listen instead", R.string.listen)
        assertActionable("like", R.string.like)
        assertActionable("dislike", R.string.dislike)
        assertActionable("watch later", R.string.watch_later_save)
    }

    @Test
    fun `a video being listened to still offers the way back to the picture`() {
        show(
            video.copy(hasVideo = false),
            quality = QualityControl.None.copy(canListen = true, listening = true),
        )

        assertActionable("watch the video again", R.string.watch_video)
    }

    /**
     * Audio has its own arrangement — transport below the artwork rather than over a video — so it
     * is a separate pass rather than an assumption that one covers the other.
     */
    @Test
    fun `the audio player still offers everything it did`() {
        show(video.copy(kind = MediaKind.PODCAST, hasVideo = false, artist = "Novara FM"))

        assertReachable("the title", "Gary Stevensons")
        assertReachable("play/pause", "Play", "Pause")
        assertReachable("skip back", "back", "Rewind")
        assertReachable("skip forward", "forward")
        assertReachable("speed", "1.5", "Speed")
        assertReachable("the sleep timer", "Sleep")
        assertReachable("skip silence", "silence")
        assertReachable("volume boost", "Boost", "boost")
        assertReachable("auto-play next", "Auto-play")
        assertReachable("fast start", "Fast start")
        assertActionable("play/pause", R.string.play, R.string.pause)
        assertActionable("speed", R.string.playback_speed)
        assertActionable("the sleep timer", R.string.sleep_timer)
        assertActionable("skip silence", R.string.skip_silence)
        assertReachable("the description", "Aaron Bastani")
        assertReachable("the up-next queue", "Up next", "Ceuta")
    }

    /**
     * The page names the show AND the network behind it.
     *
     * On the SCREEN, not through the formatter: `MediaItemSubtitleTest` calls `mediaFacts` with a
     * hand-written argument list, so it would stay green if `PlayerHeader` stopped passing the
     * publisher — which is the wiring, and the wiring is the part that has broken before in this
     * repo. The show's name is `artist`, rendered above the title; the publisher is a fact line
     * under it, and the author is deliberately NOT repeated there.
     */
    @Test
    fun `the podcast player names the show and its publisher`() {
        show(
            video.copy(
                kind = MediaKind.PODCAST,
                hasVideo = false,
                artist = "Football Daily",
                publisher = "BBC Radio 5 Live"
            )
        )

        assertReachable("the show", "Football Daily")
        assertReachable("the publisher", "BBC Radio 5 Live")
    }

    private companion object {
        /** Deep enough to show the controls, shallow enough to read in a failure message. */
        const val TREE_DEPTH = 30
    }
}
