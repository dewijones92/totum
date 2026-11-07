package com.dewijones92.totum.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.kidlock.KidTvFailure
import com.dewijones92.totum.kidlock.KidTvStatus
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.kidtv.kidTvStatusText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KidTvWordsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun says(status: KidTvStatus, expected: String, pinTitle: String? = "Peppa", tv: String? = "Living room") {
        composeTestRule.setContent { TotumTheme { Text(kidTvStatusText(status, pinTitle, tv)) } }
        composeTestRule.waitForNode(hasText(expected), 15_000)
        composeTestRule.onNodeWithText(expected).assertExists()
    }

    @Test
    fun notSetUpInvitesYouToChoose() = says(KidTvStatus.NotSetUp, "⚙️ Choose a playlist and a TV")

    @Test
    fun readyNamesThePlaylistAndTheTv() = says(KidTvStatus.Ready, "▶️ Peppa on Living room")

    @Test
    fun startingNamesTheTv() = says(KidTvStatus.Starting("Living room", "Peppa"), "⏳ Starting on Living room…")

    @Test
    fun onSaysItIsOnAndHowToStopIt() =
        says(KidTvStatus.On("Living room", "Peppa"), "✅ Peppa on Living room · tap to stop")

    @Test
    fun aTvThatIsNotSeenSaysWhatWasSeen() = says(
        KidTvStatus.Failed(KidTvFailure.TvNotFound("Living room", listOf("Kitchen", "Bedroom"))),
        "❌ Living room isn't showing up as a cast TV. Is it on? Seen: Kitchen, Bedroom",
    )

    @Test
    fun aTvThatIsNotSeenAndNothingElseEitherAsksIfItIsOn() = says(
        KidTvStatus.Failed(KidTvFailure.TvNotFound("Living room", emptyList())),
        "❌ Living room isn't showing up as a cast TV, and no other TV was found. Is it on, on the same Wi-Fi?",
    )

    @Test
    fun aPlaylistThatIsGoneSaysChooseAgain() = says(
        KidTvStatus.Failed(KidTvFailure.PlaylistGone("k")),
        "❌ The chosen playlist is no longer pinned. Choose one again.",
    )

    @Test
    fun missingLocalNetworkAccessSaysTapCastOnce() = says(
        KidTvStatus.Failed(KidTvFailure.NeedsLocalNetwork),
        "❌ Android hasn't given Totum local network access. Tap Cast once to allow it.",
    )

    @Test
    fun aCastThatDidNotStartGivesItsReason() = says(
        KidTvStatus.Failed(KidTvFailure.CastFailed("Living room", "timed out")),
        "❌ Couldn't start casting to Living room: timed out",
    )

    @Test
    fun aPlaylistThatDidNotStartGivesItsReason() = says(
        KidTvStatus.Failed(KidTvFailure.PlayFailed("Peppa", "nothing resolved")),
        "❌ Couldn't start Peppa: nothing resolved",
    )
}
