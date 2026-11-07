package com.dewijones92.totum

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.playback.SharedPrefsPlaybackSpeedStore
import com.dewijones92.totum.recorder.RecorderChoiceStore
import com.dewijones92.totum.recorder.RecorderChoices
import com.dewijones92.totum.workapps.SnoozedKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchAStoresTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var choicesBefore: RecorderChoices
    private var speedBefore = 1f

    @Before
    fun remember() {
        choicesBefore = RecorderChoiceStore(context).read()
        speedBefore = runBlocking { SharedPrefsPlaybackSpeedStore(context).speed() }
    }

    @After
    fun restore() {
        RecorderChoiceStore(context).write(choicesBefore)
        runBlocking { SharedPrefsPlaybackSpeedStore(context).save(speedBefore) }
    }

    @Test
    fun theRecorderRemembersWhatYouSwitchedOffAndStartsWithEverythingOn() {
        val store = RecorderChoiceStore(context)

        store.write(RecorderChoices(mic = false, face = true, taps = false))

        assertEquals(RecorderChoices(mic = false, face = true, taps = false), RecorderChoiceStore(context).read())
        assertEquals(RecorderChoices(mic = true, face = true, taps = true), RecorderChoices())
    }

    @Test
    fun theChosenSpeedFollowsAnExplicitSaveAndTheFirstRead() {
        val store = SharedPrefsPlaybackSpeedStore(context)

        runBlocking { store.save(1.75f) }
        assertEquals(1.75f, store.chosen.value, 0f)

        val fresh = SharedPrefsPlaybackSpeedStore(context)
        assertEquals("a store that has not been asked yet shows the default", 1f, fresh.chosen.value, 0f)
        assertEquals(1.75f, runBlocking { fresh.speed() }, 0f)
        assertEquals("and learns the saved speed once asked", 1.75f, fresh.chosen.value, 0f)
    }

    @Test
    fun snoozedNotificationKeysAreRememberedAndHandedBack() {
        val keys = SnoozedKeys(context)
        val ours = setOf("batch-a-test|1", "batch-a-test|2", "batch-a-test|3")
        try {
            ours.forEach(keys::add)
            assertTrue(SnoozedKeys(context).contains("batch-a-test|2"))

            keys.remove(setOf("batch-a-test|2"))
            assertFalse(SnoozedKeys(context).contains("batch-a-test|2"))
            assertTrue(SnoozedKeys(context).contains("batch-a-test|1"))
        } finally {
            keys.remove(ours)
        }
    }
}
