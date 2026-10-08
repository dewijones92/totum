package com.dewijones92.totum.reminders

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.dailyalarms.DailyAlarmsScreen
import com.dewijones92.totum.reminders.kit.KeepScreenAwake
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.settings.ExsurgeSettingsScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreensStayAwakeTest {

    @get:Rule
    val compose = createComposeRule()

    private val app
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TotumApplication
    private val container get() = app.container

    @Test
    fun theAlarmSettingsKeepTheScreenAwake() {
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            TotumTheme { DailyAlarmsScreen(container.dailyAlarms, onBack = {}) }
        }
        compose.waitForIdle()

        assertTrue(view!!.keepScreenOn)
    }

    @Test
    fun theExsurgePageKeepsTheScreenAwake() {
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            TotumTheme { ExsurgeSettingsScreen(container.exsurge, onBack = {}) }
        }
        compose.waitForIdle()

        assertTrue(view!!.keepScreenOn)
    }

    @Test
    fun leavingTheScreenLetsThePhoneSleepAgain() {
        var view: View? = null
        var showing by mutableStateOf(true)
        compose.setContent {
            view = LocalView.current
            if (showing) KeepScreenAwake("test")
        }
        compose.waitForIdle()
        assertTrue(view!!.keepScreenOn)

        showing = false
        compose.waitForIdle()

        assertFalse(view!!.keepScreenOn)
    }
}
