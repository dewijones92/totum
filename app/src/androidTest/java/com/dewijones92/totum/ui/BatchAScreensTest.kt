package com.dewijones92.totum.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.domain.CarryOnItem
import com.dewijones92.totum.domain.ChosenWay
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.TimeLeft
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.LiveBadge
import com.dewijones92.totum.ui.common.LocalLiveStates
import com.dewijones92.totum.ui.common.WHY_PLAYING_MENU_TAG
import com.dewijones92.totum.ui.common.WHY_PLAYING_TEXT_TAG
import com.dewijones92.totum.ui.common.WhyIsThisPlayingDialog
import com.dewijones92.totum.ui.common.WhyIsThisPlayingMenu
import com.dewijones92.totum.ui.library.CARRY_ON_TAG
import com.dewijones92.totum.ui.library.LibraryCarryOn
import com.dewijones92.totum.ui.library.carryOnCardTag
import com.dewijones92.totum.ui.library.carryOnSection
import com.dewijones92.totum.ui.queue.timeLeftWords
import com.dewijones92.totum.video.LivePhase
import com.dewijones92.totum.video.LiveState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class BatchAScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state(reason: PlayReason?) = PlaybackState(
        itemId = MediaItemId("playing"),
        title = "Playing",
        artist = null,
        artworkUrl = null,
        isPlaying = true,
        positionMs = 0,
        durationMs = 60_000,
        speed = 1f,
        wantsToPlay = true,
        playReason = reason,
    )

    private fun showWhy(reason: PlayReason?) = composeTestRule.setContent {
        TotumTheme { WhyIsThisPlayingDialog(state(reason), "full player") {} }
    }

    @Test
    fun why_the_dialog_gives_the_sentence_for_how_it_started() {
        showWhy(PlayReason.Next("Peep Show"))

        composeTestRule.waitForNode(hasTestTag(WHY_PLAYING_TEXT_TAG), 15_000)
        composeTestRule.onNodeWithTag(WHY_PLAYING_TEXT_TAG).assertTextEquals("⏭️ Next in the Peep Show group.")
    }

    @Test
    fun why_the_dialog_says_so_when_nothing_was_recorded() {
        showWhy(null)

        composeTestRule.waitForNode(hasTestTag(WHY_PLAYING_TEXT_TAG), 15_000)
        composeTestRule.onNodeWithTag(WHY_PLAYING_TEXT_TAG).assertTextEquals(
            "❔ Totum has no record of why this one started: it began before the reason was recorded.",
        )
    }

    @Test
    fun why_the_dialog_for_a_tap_and_for_a_shuffle_say_different_things() {
        showWhy(PlayReason.Chosen(ChosenWay.SHUFFLE))

        composeTestRule.waitForNode(hasTestTag(WHY_PLAYING_TEXT_TAG), 15_000)
        composeTestRule.onNodeWithTag(WHY_PLAYING_TEXT_TAG)
            .assertTextEquals("🔀 You started a shuffle and this was the first one picked.")
    }

    @Test
    fun why_the_mini_player_menu_offers_the_question_and_asks_it() {
        var asked = 0
        composeTestRule.setContent {
            TotumTheme { WhyIsThisPlayingMenu(expanded = true, onDismiss = {}, onAsk = { asked++ }) }
        }

        composeTestRule.waitForNode(hasTestTag(WHY_PLAYING_MENU_TAG), 15_000)
        composeTestRule.onNodeWithTag(WHY_PLAYING_MENU_TAG).performClick()

        assertEquals(1, asked)
    }

    private fun words(left: TimeLeft) {
        composeTestRule.setContent { TotumTheme { Text(timeLeftWords(left)) } }
    }

    @Test
    fun time_left_reads_hours_and_minutes_and_the_speed_it_will_play_at() {
        words(TimeLeft(252.minutes, unknown = 0, speed = 2f))

        composeTestRule.onNodeWithText("⏳ 4 h 12 min left at 2x").assertExists()
    }

    @Test
    fun time_left_says_at_least_when_a_length_is_unknown() {
        words(TimeLeft(30.minutes, unknown = 2, speed = null))

        composeTestRule.onNodeWithText("⏳ At least", substring = true).assertExists()
        composeTestRule.onAllNodesWithText("at 2x", substring = true).assertCountEquals(0)
    }

    @Test
    fun time_left_names_the_speed_in_the_at_least_form_too() {
        words(TimeLeft(61.minutes, unknown = 1, speed = 1.5f))

        composeTestRule.onNodeWithText("⏳ At least 1 h 1 min left at 1.5x").assertExists()
    }

    private fun episode(id: String, title: String) = PlayableItem(
        MediaItem(MediaItemId(id), SourceId("s"), title, publishedAt = null, duration = null),
        PlayHandle.Podcast(),
    )

    private fun carryOn(vararg entries: CarryOnItem, onPlay: (CarryOnItem) -> Unit = {}) {
        composeTestRule.setContent {
            TotumTheme {
                LazyColumn { carryOnSection(LibraryCarryOn(entries.toList(), onPlay)) }
            }
        }
    }

    @Test
    fun carry_on_row_shows_each_half_played_item_with_how_far_in_it_is_and_plays_it_on_a_tap() {
        val played = mutableListOf<String>()
        val first = CarryOnItem(episode("a", "Episode A"), PlayState.InProgress(120_000, 600_000))
        val second = CarryOnItem(episode("b", "Episode B"), PlayState.InProgress(60_000, 60_000 * 30))
        carryOn(first, second) { played += it.playable.item.id.value }

        composeTestRule.waitForNode(hasTestTag(CARRY_ON_TAG), 15_000)
        composeTestRule.onNodeWithText("▶️ Carry on").assertExists()
        composeTestRule.onNodeWithText("Episode A").assertExists()
        composeTestRule.onNodeWithText("▶️ 2 of 10 minutes").assertExists()
        composeTestRule.onNodeWithText("▶️ 1 of 30 minutes").assertExists()
        composeTestRule.onNodeWithTag(carryOnCardTag("b")).performClick()

        assertEquals(listOf("b"), played)
    }

    @Test
    fun carry_on_row_is_absent_when_there_is_nothing_to_carry_on() {
        carryOn()

        composeTestRule.onAllNodesWithTag(CARRY_ON_TAG).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("▶️ Carry on").assertCountEquals(0)
    }

    private val videoId = "dQw4w9WgXcQ"
    private val watch = MediaItemId("https://www.youtube.com/watch?v=$videoId")

    private fun badge(kind: MediaContentKind, phase: LivePhase?, startsAt: Instant? = null) {
        val states = phase?.let { mapOf(videoId to LiveState(videoId, it, startsAt, "", 0L, 0L)) }.orEmpty()
        composeTestRule.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalLiveStates provides remember { mutableStateOf(states) }) {
                    LiveBadge(watch, kind)
                }
            }
        }
    }

    @Test
    fun the_live_tag_follows_the_registry_whatever_kind_the_item_was_saved_as() {
        badge(MediaContentKind.STANDARD, LivePhase.LIVE)

        composeTestRule.onNodeWithText("🔴 LIVE").assertExists()
    }

    @Test
    fun an_upcoming_stream_says_starts_soon_when_it_has_no_start_time() {
        badge(MediaContentKind.STANDARD, LivePhase.UPCOMING)

        composeTestRule.onNodeWithText("🔴 STARTS SOON").assertExists()
    }

    @Test
    fun an_upcoming_stream_with_a_start_time_says_when() {
        badge(MediaContentKind.STANDARD, LivePhase.UPCOMING, Instant.now().plusSeconds(7_200))

        composeTestRule.onNodeWithText("🔴 STARTS", substring = true).assertExists()
        composeTestRule.onAllNodesWithText("🔴 STARTS SOON").assertCountEquals(0)
    }

    @Test
    fun a_queued_stream_that_ended_says_it_was_live() {
        badge(MediaContentKind.LIVE, LivePhase.ENDED)

        composeTestRule.onNodeWithText("⏺️ WAS LIVE").assertExists()
    }

    @Test
    fun a_stream_saved_as_ended_says_so_with_no_registry_entry() {
        badge(MediaContentKind.WAS_LIVE, null)

        composeTestRule.onNodeWithText("⏺️ WAS LIVE").assertExists()
    }

    @Test
    fun an_ordinary_video_carries_no_tag() {
        badge(MediaContentKind.STANDARD, null)
        composeTestRule.onAllNodesWithText("LIVE", substring = true).assertCountEquals(0)
    }

    @Test
    fun a_song_is_never_tagged_even_if_the_registry_hears_of_its_id() {
        badge(MediaContentKind.MUSIC, LivePhase.LIVE)

        composeTestRule.onAllNodesWithText("LIVE", substring = true).assertCountEquals(0)
    }
}
