package com.dewijones92.totum.exsurge

import android.app.NotificationManager
import android.content.Context
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.R
import org.junit.After
import org.junit.Assert.assertEquals
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

    @After
    fun tidy() {
        notifications.cancelSummons()
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
    fun theSummonsNotificationCarriesTheTakeoverAndItsThreeActions() {
        notifications.showSummons(TakeoverRequest(42, 2, snoozesLeft = 1, snoozeMinutes = 5, overOtherApps = false))
        val posted = manager.activeNotifications.single { it.id == ExsurgeNotifications.SUMMONS_ID }.notification
        assertNotNull(posted.fullScreenIntent)
        assertEquals(
            listOf("GO", "Snooze 5 min (left: 1)", "Skip this one"),
            posted.actions.map { it.title.toString() }
        )
    }

    @Test
    fun noSnoozeActionWhenNoneAreLeft() {
        notifications.showSummons(TakeoverRequest(42, 3, snoozesLeft = 0, snoozeMinutes = 5, overOtherApps = false))
        val posted = manager.activeNotifications.single { it.id == ExsurgeNotifications.SUMMONS_ID }.notification
        assertEquals(listOf("GO", "Skip this one"), posted.actions.map { it.title.toString() })
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
        assertEquals(listOf("Summon now", "Pause 1 hour"), banner.actions.map { it.title.toString() })
    }

    @Test
    fun theDestinationOpensWithItsRoute() {
        val ports = AndroidExsurgePorts(context) { error("playback is not part of this test") }
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
}
