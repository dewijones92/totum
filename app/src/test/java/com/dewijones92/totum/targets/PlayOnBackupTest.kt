package com.dewijones92.totum.targets

import com.dewijones92.totum.backup.BackupService
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayOnBackupTest {

    private class Base : BackupService.BackupSettings {
        override fun export(): Map<String, String> = emptyMap()

        override fun restore(values: Map<String, String>) = Unit
    }

    private var last: String? = null
    private val restored = mutableListOf<String?>()
    private val settings = Base().withPlayOnOf(read = { last }, apply = { restored += it })

    @Test
    fun `the last used device travels in the backup and is handed back on restore`() {
        last = "lg-tv"

        val exported = settings.export()
        settings.restore(exported)

        assertEquals(mapOf("play-on.v1" to "lg-tv"), exported)
        assertEquals(listOf<String?>("lg-tv"), restored)
    }

    @Test
    fun `a phone that never chose a device exports nothing, and a backup without one restores nothing`() {
        last = null

        assertEquals(emptyMap<String, String>(), settings.export())
        settings.restore(emptyMap())
        assertEquals(emptyList<String?>(), restored)
    }
}
