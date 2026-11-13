package com.dewijones92.totum.phoneuse

import android.app.usage.UsageEvents
import com.dewijones92.totum.stats.RawKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageEventKindsTest {

    @Test
    fun `android's usage events map to the five kinds the tracker reads`() {
        assertEquals(RawKind.SCREEN_ON, rawKindOf(UsageEvents.Event.SCREEN_INTERACTIVE))
        assertEquals(RawKind.SCREEN_OFF, rawKindOf(UsageEvents.Event.SCREEN_NON_INTERACTIVE))
        assertEquals(RawKind.KEYGUARD_HIDDEN, rawKindOf(UsageEvents.Event.KEYGUARD_HIDDEN))
        assertEquals(RawKind.APP_FOREGROUND, rawKindOf(UsageEvents.Event.ACTIVITY_RESUMED))
        assertEquals(RawKind.APP_BACKGROUND, rawKindOf(UsageEvents.Event.ACTIVITY_PAUSED))
    }

    @Test
    fun `everything else Android records is ignored`() {
        assertNull(rawKindOf(UsageEvents.Event.KEYGUARD_SHOWN))
        assertNull(rawKindOf(UsageEvents.Event.USER_INTERACTION))
        assertNull(rawKindOf(UsageEvents.Event.NOTIFICATION_INTERRUPTION))
        assertNull(rawKindOf(UsageEvents.Event.ACTIVITY_STOPPED))
    }
}
