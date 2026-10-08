package com.dewijones92.totum.dailyalarms

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.support.keepsScreenOn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class DailyAlarmDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = DailyAlarmNotifications(context)
    private val alarm = DailyAlarm(id = DailyAlarmController.PICKUP_ID, enabled = true)

    @After
    fun tidy() {
        notifications.hideQuestion(alarm.id)
        notifications.hideSet(alarm.id)
        RingService.stop(context)
    }

    @Test
    fun theQuestionOffersYesOtherTimeAndNo() {
        notifications.showQuestion(alarm, LocalTime.of(17, 30), last = false)
        val posted = waitFor(DailyAlarmNotifications.questionId(alarm.id))!!
        assertEquals("Pick up time: alarm at 17:30 today?", posted.extras.getString("android.title"))
        assertEquals(listOf("Yes, 17:30", "Other time", "No"), posted.actions.map { it.title.toString() })
    }

    @Test
    fun aSetAlarmOffersChangeAndCancel() {
        notifications.showSet(alarm, LocalTime.of(17, 45))
        val posted = waitFor(DailyAlarmNotifications.setId(alarm.id))!!
        assertEquals("Alarm set for 17:45", posted.extras.getString("android.title"))
        assertEquals(listOf("Change", "Cancel"), posted.actions.map { it.title.toString() })
    }

    @Test
    fun ringingGoesForegroundWithAFullScreenAlarmAndStops() {
        RingService.start(context, alarm.id, LocalTime.of(17, 30))
        val ringing = waitFor(DailyAlarmNotifications.RING_ID)
        assertNotNull("the ring service never posted its alarm", ringing)
        assertEquals(Notification.CATEGORY_ALARM, ringing!!.category)
        assertNotNull(ringing.fullScreenIntent)
        assertEquals(listOf("Snooze 5 min", "Dismiss"), ringing.actions.map { it.title.toString() })
        RingService.stop(context)
        assertEquals(null, waitFor(DailyAlarmNotifications.RING_ID, gone = true))
    }

    @Test
    fun theMorningQuestionKeepsTheScreenAwake() {
        ActivityScenario.launch<QuestionActivity>(Intent(context, QuestionActivity::class.java)).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue("the question must not let the screen dim", activity.keepsScreenOn())
            }
        }
    }

    private fun waitFor(id: Int, gone: Boolean = false): Notification? {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val found = manager.activeNotifications.firstOrNull { it.id == id }?.notification
            if (gone && found == null) return null
            if (!gone && found != null) return found
            Thread.sleep(POLL_MS)
        }
        return manager.activeNotifications.firstOrNull { it.id == id }?.notification
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 100L
    }
}
