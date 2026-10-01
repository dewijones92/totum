package com.dewijones92.totum.exsurge

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.TotumApplication
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExsurgeTakeoverFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val exsurge = (context as TotumApplication).container.exsurge
    private lateinit var before: ExsurgeSettings

    @Before
    fun summon() {
        before = exsurge.view.value.settings
        exsurge.updateSettings("test") {
            it.copy(
                enabled = true,
                quietOffice = true,
                takeoverOverApps = false,
                destinationPackage = context.packageName,
                destinationRoute = ""
            )
        }
        exsurge.dispatch(ExsurgeEvent.SummonNow, "test")
    }

    @After
    fun restore() {
        exsurge.updateSettings("test") { before.copy(enabled = false) }
        exsurge.updateSettings("test") { before }
    }

    @Test
    fun goFromTheTakeoverStartsTheBreak() {
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.Summoned)
        ActivityScenario.launch<TakeoverActivity>(TakeoverActivity.intent(context)).use {
            compose.onNodeWithTag("exsurge-go").performScrollTo().performClick()
            compose.waitUntil(5_000) { exsurge.view.value.memory.state !is ExsurgeState.Summoned }
        }
        val state = exsurge.view.value.memory.state
        assertTrue(
            "expected the break to have started, was ${state.label()}",
            state is ExsurgeState.Rising || state is ExsurgeState.OnBreak
        )
    }

    @Test
    fun skipFromTheTakeoverRecordsItAndRestartsTheClock() {
        ActivityScenario.launch<TakeoverActivity>(TakeoverActivity.intent(context)).use {
            compose.onNodeWithTag("exsurge-skip").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                val state = exsurge.view.value.memory.state
                state is ExsurgeState.Sitting || state is ExsurgeState.Dormant
            }
        }
        assertTrue(exsurge.view.value.stats.today.skipped >= 1)
    }
}
