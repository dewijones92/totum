package com.dewijones92.totum.downloads

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadHoldReleasedAtOnceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aHoldReleasedBeforeTheServiceStartsLetsGoWithoutCrashing() {
        repeat(ROUNDS) {
            DownloadKeepAliveService.hold(context, true)
            DownloadKeepAliveService.hold(context, false)
        }
        Thread.sleep(PAST_THE_FOREGROUND_DEADLINE_MS)

        assertEquals(false, DownloadKeepAliveService.holdingProcess)
    }

    private companion object {
        const val ROUNDS = 3
        const val PAST_THE_FOREGROUND_DEADLINE_MS = 15_000L
    }
}
