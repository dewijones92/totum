package com.dewijones92.totum.backup

import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.SourceSkips
import com.dewijones92.totum.settings.InMemoryAppPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceBackupTest {

    @Test
    fun `the rewind switch, the explore cap and each show's intro and outro reach a backup and come back`() {
        val phone = InMemoryAppPreferences().apply {
            setRewindOnResume(false)
            setExploreCap(5)
            setSourceSkips(SourceId("show"), SourceSkips(introEndMs = 83_000, outroLengthMs = 45_000))
            setSourceSkips(SourceId("UCchannel"), SourceSkips(introEndMs = 12_000))
        }
        val other = InMemoryAppPreferences()

        other.asBackupSettings().restore(phone.asBackupSettings().export())

        assertEquals(false, other.settings.value.rewindOnResume)
        assertEquals(5, other.settings.value.exploreCap)
        assertEquals(phone.settings.value.sourceSkips, other.settings.value.sourceSkips)
    }

    @Test
    fun `clearing both halves forgets the show`() {
        val prefs = InMemoryAppPreferences()
        prefs.setSourceSkips(SourceId("show"), SourceSkips(introEndMs = 83_000))

        prefs.setSourceSkips(SourceId("show"), SourceSkips())

        assertEquals(emptyMap<SourceId, SourceSkips>(), prefs.settings.value.sourceSkips)
    }
}
