package com.dewijones92.totum.dailyalarms

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class QuickAlarmScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 5)
    private val set = mutableListOf<Pair<String, LocalTime>>()

    private fun screenAt(hour: Int, minute: Int): DailyAlarmController {
        val instant = ZonedDateTime.of(monday.atTime(hour, minute), zone).toInstant()
        val alarms =
            DailyAlarmController(InMemoryDailyAlarmStore(), NoDailyAlarmPorts, clock = { instant }, zone = { zone })
        val actions = QuickPresetActions(
            add = { alarms.addPreset(it, "test") },
            rename = { old, new -> alarms.renamePreset(old, new, "test") },
            delete = { alarms.deletePreset(it, "test") },
        )
        composeTestRule.setContent {
            val view by alarms.view.collectAsState()
            TotumTheme {
                QuickAlarmScreen(view.presets, alarms.localNow(), actions) { title, time ->
                    set += title to time
                    alarms.setQuickAlarm(title, time, "test")
                }
            }
        }
        return alarms
    }

    @Test
    fun theStairsAreChosenAndOneTapSetsTheAlarmAQuarterOfAnHourAhead() {
        val alarms = screenAt(9, 11)

        composeTestRule.onNodeWithText("✅ ${QuickPresets.STAIRS}").assertIsDisplayed()
        composeTestRule.onNodeWithTag(QUICK_SET_TAG).performScrollTo().performClick()

        val once = alarms.view.value.oneOffs.single()
        assertEquals(QuickPresets.STAIRS, once.label)
        assertEquals(DayState.Set(monday, LocalTime.of(9, 26)), alarms.view.value.state(once.id))
    }

    @Test
    fun aNewPresetIsChosenStraightAwayAndUsedForTheAlarm() {
        val alarms = screenAt(9, 11)

        composeTestRule.onNodeWithTag(QUICK_ADD_PRESET_TAG).performClick()
        composeTestRule.onNodeWithTag(QUICK_PRESET_NAME_TAG).performTextInput("💧 Drink water")
        composeTestRule.onNodeWithTag(QUICK_PRESET_SAVE_TAG).performClick()
        composeTestRule.onNodeWithTag(QUICK_SET_TAG).performScrollTo().performClick()

        assertEquals("💧 Drink water", alarms.view.value.oneOffs.single().label)
        assertEquals(listOf("💧 Drink water", QuickPresets.STAIRS), alarms.view.value.presets)
    }

    @Test
    fun aLongPressRenamesOrDeletesAPreset() {
        val alarms = screenAt(9, 11)

        composeTestRule.onNodeWithTag(quickPresetTag(QuickPresets.STAIRS)).performTouchInput { longClick() }
        composeTestRule.onNodeWithTag(QUICK_PRESET_NAME_TAG).performTextReplacement("🏃 Stairs ×10")
        composeTestRule.onNodeWithTag(QUICK_PRESET_SAVE_TAG).performClick()
        assertEquals(listOf("🏃 Stairs ×10"), alarms.view.value.presets)

        composeTestRule.onNodeWithTag(quickPresetTag("🏃 Stairs ×10")).performTouchInput { longClick() }
        composeTestRule.onNodeWithTag(QUICK_PRESET_DELETE_TAG).performClick()
        assertEquals(emptyList<String>(), alarms.view.value.presets)
    }
}
