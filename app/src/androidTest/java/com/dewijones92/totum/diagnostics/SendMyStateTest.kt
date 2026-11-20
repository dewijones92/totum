package com.dewijones92.totum.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.di.fake.FakeAppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SendMyStateTest {

    private val resources get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun `a phone not signed in to the home server says so and sends nothing`() {
        val outcome = runBlocking { FakeAppContainer().sendMyState(resources, "a test") }

        assertEquals(StateSend.NotSignedIn, outcome)
        assertEquals(resources.getString(R.string.settings_send_state_no_sign_in), outcome.message(resources))
    }

    @Test
    fun `every outcome has its own message that starts with a state emoji`() {
        val messages = listOf(
            StateSend.Sent(SENT_BYTES),
            StateSend.NotSignedIn,
            StateSend.NotGathered,
            StateSend.Refused,
            StateSend.TestRun,
        ).map { it.message(resources) }

        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { assertTrue("\"$it\" starts with an emoji", it.first().code > ASCII_MAX) }
        assertTrue(messages.first().contains("94 KB"))
    }

    private companion object {
        const val SENT_BYTES = 94 * 1024
        const val ASCII_MAX = 127
    }
}
