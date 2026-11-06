package com.dewijones92.totum.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One boost level for the whole app, changed only when the user changes it.
 *
 * Dewi, 2026-08-05: *"volume booster settings should not change until I deliberately change them in
 * the GUI — no exceptions"*. Keyed by source, the level moved on its own whenever the queue reached
 * something from a different feed — and a boost that turns itself on is louder than the problem it
 * was solving.
 */
class GlobalVolumeBoostTest {

    private class InMemoryBoostStore : VolumeBoostStore {
        private var value: VolumeBoost? = null
        override suspend fun boost(): VolumeBoost = value ?: VolumeBoost.OFF
        override suspend fun save(boost: VolumeBoost) {
            value = boost
        }
    }

    @Test
    fun `nothing chosen yet plays unboosted`() = runTest {
        assertEquals(VolumeBoost.OFF, InMemoryBoostStore().boost())
    }

    @Test
    fun `a chosen level applies to everything afterwards`() = runTest {
        val store = InMemoryBoostStore()

        store.save(VolumeBoost.AUTO)

        assertEquals(VolumeBoost.AUTO, store.boost())
        assertEquals("and again, for the next item from anywhere", VolumeBoost.AUTO, store.boost())
    }

    @Test
    fun `turning it back off is remembered like any other choice`() = runTest {
        val store = InMemoryBoostStore()

        store.save(VolumeBoost.AUTO)
        store.save(VolumeBoost.OFF)

        assertEquals(VolumeBoost.OFF, store.boost())
    }

    @Test
    fun `the no-op store always reports off`() = runTest {
        NoOpVolumeBoostStore.save(VolumeBoost.AUTO)

        assertEquals(VolumeBoost.OFF, NoOpVolumeBoostStore.boost())
    }
}
