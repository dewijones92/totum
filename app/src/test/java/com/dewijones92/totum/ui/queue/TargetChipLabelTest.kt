package com.dewijones92.totum.ui.queue

import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetChipLabelTest {

    private val lg = Target("lg-tv", "LG TV", TargetKind.TV)

    @Test
    fun `a TV that is on is its kind's emoji and its name`() {
        assertEquals("📺 LG TV", lg.chipLabel())
        assertEquals("📺 LG TV", lg.chipLabel(TargetPower.ON))
    }

    @Test
    fun `a sleeping TV wears 💤 and a waking one ⏰, so the chip says it before anything is tapped`() {
        assertEquals("📺 LG TV 💤", lg.chipLabel(TargetPower.ASLEEP))
        assertEquals("📺 LG TV ⏰", lg.chipLabel(TargetPower.WAKING))
    }
}
