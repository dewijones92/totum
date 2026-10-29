package com.dewijones92.totum.dailyalarms

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class NewAlarmSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 5)
    private val store = InMemoryDailyAlarmStore(recentTitles = listOf("Bins out"))

    private fun pageAt(hour: Int, minute: Int = 0): DailyAlarmController {
        val instant = ZonedDateTime.of(monday.atTime(hour, minute), zone).toInstant()
        val alarms = DailyAlarmController(store, NoDailyAlarmPorts, clock = { instant }, zone = { zone })
        composeTestRule.setContent { TotumTheme { DailyAlarmsScreen(alarms, onBack = {}, startWithNewAlarm = false) } }
        return alarms
    }

    @Test
    fun aTitleAndPlusFifteenMinutesSetsAOneOffAlarmThatIsListed() {
        val alarms = pageAt(hour = 18, minute = 47)

        composeTestRule.onNodeWithTag(NEW_ALARM_TAG).performClick()
        composeTestRule.onNodeWithTag(NEW_ALARM_TITLE_TAG).performTextInput("Deployed to production")
        composeTestRule.onNodeWithText("+15 min").performScrollTo().performClick()
        composeTestRule.onNodeWithTag(NEW_ALARM_SET_TAG).performScrollTo().performClick()

        val once = alarms.view.value.oneOffs.single()
        assertEquals("Deployed to production", once.label)
        assertEquals(DayState.Set(monday, LocalTime.of(19, 2)), alarms.view.value.state(once.id))
        composeTestRule.onNodeWithTag("oneoff-card-Deployed to production").assertExists()
    }

    @Test
    fun aRecentTitleFillsTheTitleInOneTap() {
        val alarms = pageAt(hour = 7)

        composeTestRule.onNodeWithTag(NEW_ALARM_TAG).performClick()
        composeTestRule.onNodeWithText("Bins out").performClick()
        composeTestRule.onNodeWithText("+1 h").performScrollTo().performClick()
        composeTestRule.onNodeWithTag(NEW_ALARM_SET_TAG).performScrollTo().performClick()

        assertEquals("Bins out", alarms.view.value.oneOffs.single().label)
    }

    @Test
    fun setStaysOffUntilATimeIsChosen() {
        pageAt(hour = 7)

        composeTestRule.onNodeWithTag(NEW_ALARM_TAG).performClick()

        composeTestRule.onNodeWithTag(NEW_ALARM_SET_TAG).assertIsNotEnabled()
    }
}
