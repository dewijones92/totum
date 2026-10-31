package com.dewijones92.totum.dailyalarms

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SetTodayFromThePageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 5)

    private val questions = mutableListOf<AlarmEffect.ShowQuestion>()
    private val ports = object : DailyAlarmPorts by NoDailyAlarmPorts {
        override fun showQuestion(alarm: DailyAlarm, question: AlarmEffect.ShowQuestion) {
            questions += question
        }
    }

    private fun pageAt(hour: Int): DailyAlarmController {
        val instant = ZonedDateTime.of(monday.atTime(hour, 0), zone).toInstant()
        val alarms = DailyAlarmController(InMemoryDailyAlarmStore(), ports, clock = { instant }, zone = { zone })
        composeTestRule.setContent { TotumTheme { DailyAlarmsScreen(alarms, onBack = {}) } }
        return alarms
    }

    @Test
    fun aTimeTappedOnThePageTurnsTheAlarmOnAndSetsToday() {
        val alarms = pageAt(hour = 6)
        assertTrue(alarms.view.value.alarms.single().enabled.not())

        composeTestRule.onNodeWithTag("dailyalarm-today-pickup-17:30").performScrollTo().performClick()

        assertTrue(alarms.view.value.alarms.single().enabled)
        assertEquals(DayState.Set(monday, LocalTime.of(17, 30)), alarms.view.value.state("pickup"))
    }

    @Test
    fun settingItMidMorningNeverShowsTheMorningQuestion() {
        val alarms = pageAt(hour = 10)

        composeTestRule.onNodeWithTag("dailyalarm-today-pickup-17:30").performScrollTo().performClick()

        assertEquals(DayState.Set(monday, LocalTime.of(17, 30)), alarms.view.value.state("pickup"))
        assertEquals(emptyList<AlarmEffect.ShowQuestion>(), questions)
    }

    @Test
    fun cancelTodayUndoesIt() {
        val alarms = pageAt(hour = 6)
        composeTestRule.onNodeWithTag("dailyalarm-today-pickup-17:30").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("dailyalarm-today-cancel-pickup").performScrollTo().performClick()

        assertEquals(DayState.Done(monday, Outcome.CANCELLED), alarms.view.value.state("pickup"))
    }

    @Test
    fun aTimeAlreadyGoneCannotBeTapped() {
        pageAt(hour = 18)

        composeTestRule.onNodeWithTag("dailyalarm-today-pickup-17:30").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theTimesAreButtonsAndNotTodayCanBeSaidBeforeTheQuestion() {
        val alarms = pageAt(hour = 6)
        alarms.update("test") { list -> list.map { it.copy(enabled = true) } }

        composeTestRule.onNodeWithTag("dailyalarm-today-pickup-17:30").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeTestRule.onNodeWithTag("dailyalarm-today-no-pickup").performScrollTo().performClick()

        assertEquals(DayState.Done(monday, Outcome.DECLINED), alarms.view.value.state("pickup"))
    }
}
