package com.dewijones92.totum.exsurge

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class ExsurgeStatusCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/London")
    private val tenAm = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, zone).toInstant()
    private val settings = ExsurgeSettings(enabled = true)
    private val summons = Summons(3, tenAm, breakMinutes = 10)

    private fun view(state: ExsurgeState, minutesLater: Long) = tenAm.plus(Duration.ofMinutes(minutesLater)).let { at ->
        ExsurgeView(
            settings = settings,
            memory = ExsurgeMemory(state),
            stats = ExsurgeStats.of(emptyList(), at, zone, 0),
            nextWake = null,
            stepsAvailable = true,
            at = at,
            zone = zone,
        )
    }

    private fun showsWhatTheBannerShows(view: ExsurgeView): List<BannerAction> {
        val clicked = mutableListOf<BannerAction>()
        compose.setContent { TotumTheme { ExsurgeStatusCard(view, onAction = { clicked += it }, ticking = false) } }
        val (title, detail, progress) = BannerText(context).describe(view)
        compose.onNodeWithTag("exsurge-status-title").assertTextEquals(title)
        compose.onNodeWithTag("exsurge-status-detail").assertTextEquals(detail)
        if (progress != null) compose.onNodeWithTag("exsurge-status-progress").assertIsDisplayed()
        view.actions.forEach { compose.onNodeWithTag("exsurge-action-${it.name.lowercase()}").performClick() }
        return clicked
    }

    @Test
    fun sittingShowsTheNextSummonsTheBarAndTheIdleButtons() {
        val view = view(ExsurgeState.Sitting(tenAm), minutesLater = 12)
        assertEquals(view.actions, showsWhatTheBannerShows(view))
    }

    @Test
    fun aBreakShowsItsOwnEndAndProgress() {
        val view = view(ExsurgeState.OnBreak(summons, tenAm, null, 14, stepsProven = true), minutesLater = 3)
        assertEquals(emptyList<BannerAction>(), showsWhatTheBannerShows(view))
    }

    @Test
    fun aSnoozeOffersGoLikeTheBanner() {
        val view = view(ExsurgeState.Snoozed(summons, tenAm.plus(Duration.ofMinutes(5))), minutesLater = 1)
        assertEquals(listOf(BannerAction.GO), showsWhatTheBannerShows(view))
    }
}
