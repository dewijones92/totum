package com.dewijones92.totum.targets

import com.dewijones92.totum.data.targets.PlayOn
import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMarksTest {

    private val lg = Target("lg-tv", "LG TV", TargetKind.TV)
    private val bar = Target("bar", "Soundbar", TargetKind.SPEAKER)
    private val atHome = listOf(PlayOnOption(PlayOn.phone), PlayOnOption(lg), PlayOnOption(bar))
    private val colours = mapOf("phone" to 0xFFFF8A3DL, "lg-tv" to 0xFF0089B0L, "bar" to 0xFF7B3FE4L)

    @Test
    fun `the item on this phone and the item on a device each wear that device's colour`() {
        val marks = DeviceMarkRules.marks(mapOf("b" to lg), atHome, colours, phoneItemId = "a")

        assertEquals(DeviceMark("phone", "📱 This phone", 0xFFFF8A3DL), marks["a"])
        assertEquals(DeviceMark("lg-tv", "📺 LG TV", 0xFF0089B0L), marks["b"])
    }

    @Test
    fun `an item playing on a device is that device's even when this phone also has it`() {
        val marks = DeviceMarkRules.marks(mapOf("a" to bar), atHome, colours, phoneItemId = "a")

        assertEquals("bar", marks.getValue("a").deviceId)
    }

    @Test
    fun `with only this phone on offer nothing is marked, since there is nothing to tell apart`() {
        val marks = DeviceMarkRules.marks(emptyMap(), listOf(PlayOnOption(PlayOn.phone)), colours, phoneItemId = "a")

        assertTrue(marks.isEmpty())
    }

    @Test
    fun `a mark, a picker dot and a chip read one colour per device through the one rule`() {
        val marks = DeviceMarkRules.marks(mapOf("b" to lg, "c" to bar), atHome, colours, phoneItemId = "a")

        assertEquals(TargetColours.of(colours, "phone"), marks.getValue("a").colour)
        assertEquals(TargetColours.of(colours, "lg-tv"), marks.getValue("b").colour)
        assertEquals(TargetColours.of(colours, "bar"), marks.getValue("c").colour)
    }

    @Test
    fun `a device with no colour yet gets the phone's tangerine rather than none`() {
        val marks = DeviceMarkRules.marks(mapOf("b" to lg), atHome, emptyMap(), phoneItemId = null)

        assertEquals(TargetColours.PHONE, marks.getValue("b").colour)
    }
}
