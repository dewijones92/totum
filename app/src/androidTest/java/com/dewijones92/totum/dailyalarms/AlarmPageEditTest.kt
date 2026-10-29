package com.dewijones92.totum.dailyalarms

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmPageEditTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 5)
    private val store = InMemoryDailyAlarmStore()

    private fun pageAt(hour: Int, minute: Int = 0): DailyAlarmController {
        val instant = ZonedDateTime.of(monday.atTime(hour, minute), zone).toInstant()
        val alarms = DailyAlarmController(
            store,
            NoDailyAlarmPorts,
            clock = { instant },
            zone = { zone },
            seeds = listOf(DailyAlarm.FRIDGE),
        )
        alarms.tickAll("test")
        composeTestRule.setContent { TotumTheme { DailyAlarmsScreen(alarms, onBack = {}) } }
        return alarms
    }

    private fun fridge(alarms: DailyAlarmController) = alarms.view.value.alarms.single { it.id == "fridge" }

    @Test
    fun theFridgeReminderIsOnThePageSetForTonight() {
        pageAt(hour = 18)
        composeTestRule.onNodeWithTag("dailyalarm-next-fridge").performScrollTo().assertTextEquals("21:00")
        composeTestRule.onNodeWithText("✅ Today: reminds you at 21:00").assertExists()
    }

    @Test
    fun plusAndMinusFifteenMoveTheTimeAndTonightsReminder() {
        val alarms = pageAt(hour = 18)
        composeTestRule.onNodeWithTag("dailyalarm-later-fridge").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("dailyalarm-later-fridge").performClick()
        composeTestRule.onNodeWithTag("dailyalarm-earlier-fridge").performClick()
        assertEquals(LocalTime.of(21, 15), fridge(alarms).defaultTime)
        assertEquals(DayState.Set(monday, LocalTime.of(21, 15)), alarms.view.value.state("fridge"))
        composeTestRule.onNodeWithTag("dailyalarm-next-fridge").assertTextEquals("21:15")
    }

    @Test
    fun tappingTheTimeOpensTheClockStraightAway() {
        pageAt(hour = 18)
        composeTestRule.onNodeWithTag("dailyalarm-next-fridge").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("time-picker").assertExists()
        composeTestRule.onNodeWithTag("time-picker-mode").performClick()
        composeTestRule.onNodeWithTag("time-input").assertExists()
    }

    @Test
    fun aDayIsToggledFromTheCard() {
        val alarms = pageAt(hour = 18)
        composeTestRule.onNodeWithTag("dailyalarm-day-fridge-SUNDAY").performScrollTo().performClick()
        assertTrue(DayOfWeek.SUNDAY !in fridge(alarms).days)
    }

    @Test
    fun aDeleteCanBeUndone() {
        val alarms = pageAt(hour = 18)
        composeTestRule.onNodeWithTag("dailyalarm-edit-fridge").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("dailyalarm-delete-fridge").performScrollTo().performClick()
        assertTrue(alarms.view.value.alarms.none { it.id == "fridge" })
        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitUntil(5_000) { alarms.view.value.alarms.any { it.id == "fridge" } }
    }

    @Test
    fun theNewSheetSetsARepeatingReminderThatStartsOn() {
        val alarms = pageAt(hour = 18)
        composeTestRule.onNodeWithTag(NEW_ALARM_TAG).performClick()
        composeTestRule.onNodeWithTag(NEW_ALARM_TITLE_TAG).performTextInput("Lock the back door")
        composeTestRule.onNodeWithTag("new-kind-reminder").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("new-repeat-every_day").performScrollTo().performClick()
        composeTestRule.onNodeWithTag(NEW_ALARM_PICK_TIME_TAG).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("time-picker-ok").performClick()
        composeTestRule.onNodeWithTag(NEW_ALARM_SET_TAG).performScrollTo().performClick()

        val made = alarms.view.value.daily.single { it.label == "Lock the back door" }
        assertEquals(AlarmKind.REMINDER, made.kind)
        assertTrue(made.enabled)
        assertEquals(DailyAlarm.EVERY_DAY, made.days)
    }

    @Test
    fun aOneOffsTimeAndTitleCanBeChanged() {
        val alarms = pageAt(hour = 18)
        alarms.addOneOff("Deploy", monday, LocalTime.of(19, 0), "test")
        composeTestRule.onNodeWithTag("oneoff-title-Deploy").performClick()
        composeTestRule.onNodeWithTag("oneoff-rename-field").performTextReplacement("Deploy to production")
        composeTestRule.onNodeWithTag("oneoff-rename-ok").performClick()
        assertEquals("Deploy to production", alarms.view.value.oneOffs.single().label)
    }
}
