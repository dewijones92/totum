package com.dewijones92.totum.exsurge

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek

class ExsurgeBannerFlowTest {
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val application = ApplicationProvider.getApplicationContext<TotumApplication>()
    private val exsurge = application.container.exsurge
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var before: ExsurgeSettings

    @Before
    fun startOff() {
        before = exsurge.view.value.settings
        instrumentation.uiAutomation.grantRuntimePermission(
            application.packageName,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        instrumentation.runOnMainSync {
            exsurge.dispatch(ExsurgeEvent.TurnOff, "test")
            exsurge.updateSettings("test") {
                it.copy(
                    enabled = false,
                    quietOffice = true,
                    takeoverOverApps = false,
                    activeDays = DayOfWeek.entries.toSet(),
                    startMinuteOfDay = 0,
                    endMinuteOfDay = 1440
                )
            }
            exsurge.refresh()
        }
        waitFor { !ExsurgeBannerService.running && banner() != null }
    }

    @After
    fun restore() {
        instrumentation.runOnMainSync {
            exsurge.dispatch(ExsurgeEvent.TurnOff, "test")
            exsurge.updateSettings("test") { before }
            exsurge.refresh()
        }
    }

    @Test
    fun turnOnThenOffKeepsTheBannerAfterTheForegroundServiceStops() {
        action("Turn on").actionIntent.send()
        waitFor {
            exsurge.view.value.settings.enabled && ExsurgeBannerService.running &&
                (banner()?.flags ?: 0) and Notification.FLAG_FOREGROUND_SERVICE != 0
        }
        instrumentation.runOnMainSync { exsurge.updateSettings("test") { it.copy(enabled = false) } }
        waitFor { !ExsurgeBannerService.running && offBanner() }
        Thread.sleep(POST_WAIT_MS)
        assertTrue("the system cancelled the banner after service shutdown", offBanner())
        assertEquals(listOf("Turn on", "Summon now", "Restart clock"), banner()!!.actions.map { it.title.toString() })
    }

    @Test
    fun theOffBannerRepostsAfterDismissalAndSystemRearming() {
        val delete = banner()!!.deleteIntent
        manager.cancel(ExsurgeNotifications.BANNER_ID)
        delete.send()
        waitFor(::offBanner)
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { systemAction ->
            manager.cancel(ExsurgeNotifications.BANNER_ID)
            instrumentation.runOnMainSync { ExsurgeActionReceiver().onReceive(application, Intent(systemAction)) }
            waitFor(::offBanner)
        }
        assertFalse(exsurge.view.value.settings.enabled)
        assertFalse(ExsurgeBannerService.running)
    }

    @Test
    fun theOffQuickActionsRunOnceAndReturnToOff() {
        repeat(5) {
            action("Restart clock").actionIntent.send()
            waitFor { exsurge.view.value.memory.state is ExsurgeState.Sitting }
            assertTrue((exsurge.view.value.memory.state as ExsurgeState.Sitting).oneOff)
            assertFalse(exsurge.view.value.settings.enabled)
            action("Summon now").actionIntent.send()
            waitFor { exsurge.view.value.memory.state is ExsurgeState.Summoned }
            assertFalse(exsurge.view.value.settings.enabled)
            ExsurgeActionReceiver.pending(application, ExsurgeActionReceiver.SKIP).send()
            waitFor {
                exsurge.view.value.memory.state == ExsurgeState.Off && !ExsurgeBannerService.running && offBanner()
            }
        }
    }

    private fun banner(): Notification? {
        val posted = manager.activeNotifications
        return (
            posted.firstOrNull { it.id == ExsurgeNotifications.FOREGROUND_ID }
                ?: posted.firstOrNull { it.id == ExsurgeNotifications.BANNER_ID }
            )?.notification
    }

    private fun offBanner(): Boolean = manager.activeNotifications.let { posted ->
        posted.none { it.id == ExsurgeNotifications.FOREGROUND_ID } &&
            posted.firstOrNull { it.id == ExsurgeNotifications.BANNER_ID }
                ?.notification?.extras?.getString("android.title") == "Exsurge et Disce is off"
    }

    private fun action(title: String): Notification.Action {
        var found: Notification.Action? = null
        waitFor {
            found = banner()?.actions?.firstOrNull { it.title.toString() == title }
            found != null
        }
        return checkNotNull(found) { "no banner offered \"$title\" within ${WAIT_MS}ms; banner=${banner()?.extras}" }
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        error(
            "notification state never settled: ${exsurge.view.value.memory.state.label()}, " +
                "service=${ExsurgeBannerService.running}, title=${banner()?.extras?.getString("android.title")}, " +
                "flags=${banner()?.flags}"
        )
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POST_WAIT_MS = 1_000L
        const val POLL_MS = 50L
    }
}
