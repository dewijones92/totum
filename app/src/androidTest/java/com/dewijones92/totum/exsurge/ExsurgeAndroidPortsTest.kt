package com.dewijones92.totum.exsurge

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ExsurgeAndroidPortsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifications = ExsurgeNotifications(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun postedSummons(): Notification = posted(ExsurgeNotifications.SUMMONS_ID)

    private fun posted(id: Int, titled: String? = null): Notification {
        val deadline = System.currentTimeMillis() + POST_WAIT_MS
        var last: Notification? = null
        while (System.currentTimeMillis() < deadline) {
            last = manager.activeNotifications.firstOrNull { it.id == id }?.notification
            if (last != null && (titled == null || last.extras.getString("android.title") == titled)) return last
            Thread.sleep(POLL_MS)
        }
        return last ?: error("notification $id was never posted")
    }

    @After
    fun tidy() {
        notifications.cancelSummons()
        val original = (context.applicationContext as TotumApplication).container.exsurge.view.value
        ExsurgeBannerService.reconcile(context, original, notifications)
    }

    @Test
    fun everyVoiceClipDecodesOnTheDevice() {
        val clips = listOf(
            R.raw.exsurge_summon, R.raw.exsurge_summon_louder, R.raw.exsurge_summon_oration, R.raw.exsurge_go,
            R.raw.exsurge_risen, R.raw.exsurge_two_minutes, R.raw.exsurge_free_1, R.raw.exsurge_free_2,
            R.raw.exsurge_skipped, R.raw.exsurge_promoted,
        )
        clips.forEach { clip ->
            val player = MediaPlayer()
            context.resources.openRawResourceFd(
                clip
            ).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            player.prepare()
            assertTrue("clip ${context.resources.getResourceEntryName(clip)} has no duration", player.duration > 500)
            player.release()
        }
    }

    @Test
    fun theSummonsNotificationCarriesTheTakeoverAndGoSnoozeSkip() {
        notifications.showSummons(TakeoverRequest(42, 2, snoozeMinutes = 10, overOtherApps = false))
        val posted = postedSummons()
        assertNotNull(posted.fullScreenIntent)
        assertEquals(listOf("GO", "Snooze 10 min", "Skip this one"), posted.actions.map { it.title.toString() })
    }

    @Test
    fun snoozeIsOfferedHoweverManyTimesItHasBeenUsed() {
        notifications.showSummons(TakeoverRequest(42, 3, snoozeMinutes = 5, overOtherApps = false))
        assertEquals("Snooze 5 min", postedSummons().actions[1].title.toString())
    }

    @Test
    fun theBannerSaysWhenTheNextSummonsIs() {
        val zone = ZoneId.of("Europe/London")
        val since = Instant.parse("2026-10-05T09:00:00Z")
        val view = ExsurgeView(
            settings = ExsurgeSettings(enabled = true),
            memory = ExsurgeMemory(ExsurgeState.Sitting(since)),
            stats = ExsurgeStats.of(emptyList(), since, zone),
            nextWake = null,
            stepsAvailable = true,
            at = since.plusSeconds(17 * 60),
            zone = zone,
        )
        val banner = notifications.banner(view)
        assertEquals("Next summons 10:30", banner.extras.getString("android.title"))
        assertEquals(
            "Sat 17 of 30 min · laurels today: 0 · Tiro",
            banner.extras.getCharSequence("android.text").toString()
        )
        assertTrue(banner.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(banner.deleteIntent)
        assertEquals(ExsurgeNotifications.screenIntent(context), banner.contentIntent)
        assertEquals(listOf("Summon now", "Restart clock", "Pause 1 hour"), banner.actions.map { it.title.toString() })
    }

    @Test
    fun theOffBannerStaysPostedWithAllThreeUsefulActions() {
        val now = Instant.parse("2026-10-05T09:00:00Z")
        val zone = ZoneId.of("Europe/London")
        val off = ExsurgeView(
            settings = ExsurgeSettings(),
            memory = ExsurgeMemory(),
            stats = ExsurgeStats.of(emptyList(), now, zone),
            nextWake = null,
            stepsAvailable = false,
            at = now,
            zone = zone,
        )
        ExsurgeBannerService.reconcile(context, off, notifications)
        val posted = posted(ExsurgeNotifications.BANNER_ID, titled = "Exsurge et Disce is off")
        assertEquals("Exsurge et Disce is off", posted.extras.getString("android.title"))
        assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(posted.deleteIntent)
        assertEquals(listOf("Turn on", "Summon now", "Restart clock"), posted.actions.map { it.title.toString() })
        assertEquals(ExsurgeNotifications.screenIntent(context), posted.contentIntent)
    }

    @Test
    fun pausedAndOutOfHoursKeepAQuietOngoingBanner() {
        val now = Instant.parse("2026-10-05T09:00:00Z")
        val zone = ZoneId.of("Europe/London")
        val cases = listOf(
            ExsurgeState.Paused(now.plusSeconds(3600)) to "Paused until 11:00",
            ExsurgeState.Dormant(now.plusSeconds(86400)) to "Surgius sleeps · back Tue 10:00",
        )
        cases.forEach { (state, title) ->
            val view = ExsurgeView(
                settings = ExsurgeSettings(enabled = true),
                memory = ExsurgeMemory(state),
                stats = ExsurgeStats.of(emptyList(), now, zone),
                nextWake = null,
                stepsAvailable = false,
                at = now,
                zone = zone,
            )
            ExsurgeBannerService.reconcile(context, view, notifications)
            val posted = posted(ExsurgeNotifications.BANNER_ID, titled = title)
            assertEquals(title, posted.extras.getString("android.title"))
            assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(listOf("Summon now", "Restart clock"), posted.actions.map { it.title.toString() })
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
    }

    @Test
    fun theDestinationOpensWithItsRoute() {
        val ports = AndroidExsurgePorts(context, { error("playback is not part of this test") }) {
            error("playback is not part of this test")
        }
        val own = ExsurgeSettings(destinationPackage = context.packageName, destinationRoute = "/practice")
        val intent = AndroidExsurgePorts.destinationIntent(context, own)!!
        assertEquals(context.packageName, intent.`package` ?: intent.component?.packageName)
        assertEquals("/practice", intent.getStringExtra(AndroidExsurgePorts.LOQUAX_ROUTE_EXTRA))
        assertTrue(ports.openDestination(own))
        assertEquals(false, ports.openDestination(own.copy(destinationPackage = "no.such.app")))
    }

    @Test
    fun theFaceAndLogoRender() {
        Mood.entries.forEach { mood ->
            val bitmap = SurgiusPainter.faceBitmap(128, mood)
            assertTrue("$mood drew nothing", bitmap.getPixel(64, 64) != 0)
        }
        assertTrue(SurgiusPainter.logoBitmap(200).getPixel(100, 100) != 0)
        assertTrue(SurgiusPainter.glyphBitmap(96).let { bitmap -> (0 until 96).any { bitmap.getPixel(it, 48) != 0 } })
    }

    private companion object {
        const val POST_WAIT_MS = 3_000L
        const val POLL_MS = 50L
    }
}
