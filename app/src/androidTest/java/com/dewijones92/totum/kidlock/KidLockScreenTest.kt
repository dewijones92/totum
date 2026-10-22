package com.dewijones92.totum.kidlock

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.TopLayers
import com.dewijones92.totum.ui.player.PlaybackToggles
import com.dewijones92.totum.ui.player.SecondaryControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KidLockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun `the player Kid lock tile locks and the locked stage covers the app and closes the player`() {
        val container = FakeAppContainer()
        var playerClosed = 0
        compose.setContent {
            TotumTheme {
                SecondaryControls(PlaybackToggles(onKidLock = { container.kidLock.lock("test tile") }))
                TopLayers(container, still = false) { playerClosed++ }
            }
        }

        compose.onNodeWithText(text(R.string.kid_lock)).performClick()
        compose.waitForIdle()

        assertTrue(container.kidLock.locked.value)
        compose.onNodeWithContentDescription(text(R.string.kid_lock_stage)).assertIsDisplayed()
        assertEquals(1, playerClosed)
    }
}
