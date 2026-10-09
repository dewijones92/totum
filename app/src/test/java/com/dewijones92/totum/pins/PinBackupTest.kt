package com.dewijones92.totum.pins

import com.dewijones92.totum.backup.BackupService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinBackupTest {

    private val plain = object : BackupService.BackupSettings {
        var restored: Map<String, String> = emptyMap()
        override fun export(): Map<String, String> = mapOf("autoplay" to "true")
        override fun restore(values: Map<String, String>) {
            restored = values
        }
    }

    private val album = Pin.Album("MPREb_x", "Guitar Recital", null)
    private val show = Pin.Show("feed-1", "https://feed.test/rss", "A show", null)

    @Test
    fun `a backup carries the pins beside the settings`() {
        val exported = plain.withPins(InMemoryPinStore(listOf(album))).export()

        assertEquals("true", exported["autoplay"])
        assertEquals(listOf(album), PinCodec.decode(exported.getValue("pins.v1")))
    }

    @Test
    fun `restoring adds the backup's pins to the ones already here, once each`() {
        val backup = plain.withPins(InMemoryPinStore(listOf(album, show))).export()
        val here = InMemoryPinStore(listOf(show))

        plain.withPins(here).restore(backup)

        assertEquals(listOf(show.key, album.key), here.pins.value.map { it.key })
        assertTrue(plain.restored.containsKey("autoplay"))
    }
}
