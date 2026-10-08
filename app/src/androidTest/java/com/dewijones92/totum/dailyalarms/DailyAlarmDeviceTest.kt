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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class DailyAlarmDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = DailyAlarmNotifications(context)
    private val alarm = DailyAlarm(id = DailyAlarmController.PICKUP_ID, enabled = true)
    private val zone = ZoneId.of("Europe/London")

    @After
    fun tidy() {
        notifications.hideQuestion(alarm.id)
        AlarmBoardService.reconcile(context, alarmBoard(emptyList(), emptyMap(), Instant.now(), zone))
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
    fun theBoardIsOnePinnedNotificationSoonestFirst() {
        val monday = LocalDate.of(2026, 10, 5)
        val gym = DailyAlarm(id = "gym", label = "Gym bag", enabled = true, defaultTime = LocalTime.of(18, 0))
        val board = alarmBoard(
            listOf(gym, alarm),
            mapOf(alarm.id to DayState.Set(monday, LocalTime.of(17, 30)), gym.id to DayState.Asking(monday, asks = 1)),
            ZonedDateTime.of(monday.atTime(14, 0), zone).toInstant(),
            zone,
        )

        AlarmBoardService.reconcile(context, board)
        val posted = waitFor(DailyAlarmNotifications.BOARD_ID, titled = "Next: Pick up time 17:30")!!

        assertEquals("Next: Pick up time 17:30", posted.extras.getString("android.title"))
        val lines = posted.extras.getCharSequence("android.bigText").toString().lines()
        assertTrue(lines[0], lines[0].startsWith("17:30 Pick up time"))
        assertTrue(lines[1], lines[1].startsWith("18:00 Gym bag"))
        assertEquals(listOf("Change 17:30", "Cancel 17:30", "Open"), posted.actions.map { it.title.toString() })
        assertTrue("pinned by a foreground service", posted.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue("swiping it away puts it back", posted.deleteIntent?.isBroadcast == true)
        val channel = manager.getNotificationChannel(posted.channelId)
        assertEquals(
            "alerting, so a Pixel shows it on the lock screen",
            NotificationManager.IMPORTANCE_DEFAULT,
            channel.importance
        )
        assertTrue(
            "the channel does not hide it on the lock screen",
            channel.lockscreenVisibility !in setOf(Notification.VISIBILITY_PRIVATE, Notification.VISIBILITY_SECRET),
        )
        assertEquals("shown in full on the lock screen", Notification.VISIBILITY_PUBLIC, posted.visibility)
        assertEquals("no sound", null, channel.sound)
        assertFalse("no buzz", channel.shouldVibrate())
    }

    @Test
    fun theBoardStaysUpWhenNothingIsLeftToday() {
        val monday = LocalDate.of(2026, 10, 5)
        val now = ZonedDateTime.of(monday.atTime(18, 0), zone).toInstant()

        AlarmBoardService.reconcile(
            context,
            alarmBoard(listOf(alarm), mapOf(alarm.id to DayState.Done(monday, Outcome.DECLINED)), now, zone)
        )

        val posted = waitFor(DailyAlarmNotifications.BOARD_ID, titled = "No alarm today")
        assertEquals("No alarm today", posted?.extras?.getString("android.title"))
        assertTrue(posted!!.extras.getCharSequence("android.bigText").toString().startsWith("17:30 Pick up time"))
    }

    @Test
    fun withEveryAlarmOffTheBoardOffersToTurnThemOn() {
        val now = ZonedDateTime.of(LocalDate.of(2026, 10, 5).atTime(9, 0), zone).toInstant()

        AlarmBoardService.reconcile(context, alarmBoard(listOf(alarm.copy(enabled = false)), emptyMap(), now, zone))

        val posted = waitFor(DailyAlarmNotifications.BOARD_ID, titled = "Alarms off")
        assertEquals("Alarms off", posted?.extras?.getString("android.title"))
        assertEquals(listOf("Turn on", "Open"), posted!!.actions.map { it.title.toString() })
        assertTrue("still pinned", posted.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
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

    private fun waitFor(id: Int, gone: Boolean = false, titled: String? = null): Notification? {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val found = manager.activeNotifications.firstOrNull { it.id == id }?.notification
                ?.takeIf { titled == null || it.extras.getString("android.title") == titled }
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
