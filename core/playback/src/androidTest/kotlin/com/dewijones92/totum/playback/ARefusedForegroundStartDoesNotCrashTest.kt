package com.dewijones92.totum.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ARefusedForegroundStartDoesNotCrashTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun notification(): MediaNotification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("test", "test", NotificationManager.IMPORTANCE_LOW))
        val built = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_media_play).build()
        return MediaNotification(1, built)
    }

    @Test
    fun aRefusedForegroundStartFromALateUpdateIsLoggedNotThrown() {
        var calls = 0
        val refusing = MediaNotification.Provider.Callback {
            calls++
            throw ForegroundServiceStartNotAllowedException(
                "startForegroundService() not allowed due to mAllowStartForeground false"
            )
        }

        RefusalTolerantNotificationProvider.tolerant(refusing).onNotificationChanged(notification())

        assertEquals(1, calls)
    }

    @Test
    fun anyOtherFailureStillSurfaces() {
        val broken = MediaNotification.Provider.Callback { throw IllegalStateException("something else") }

        assertThrows(IllegalStateException::class.java) {
            RefusalTolerantNotificationProvider.tolerant(broken).onNotificationChanged(notification())
        }
    }
}
