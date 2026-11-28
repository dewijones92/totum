package com.dewijones92.totum.data.targets

import com.dewijones92.totum.data.backup.BackupItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetBarRulesTest {

    private val item = BackupItem("a", "feed", "Episode one", contentKind = "STANDARD", playbackType = "AUDIO")
    private val tv = Target("lg-tv", "LG TV", TargetKind.TV)
    private val bar = Target("bar", "Soundbar", TargetKind.SPEAKER)

    private fun playingTv(at: Int = 0) = TargetState(tv, entries = listOf(TargetEntry(item)), at = at, playing = true)

    @Test
    fun `the bar is the selected device, but only while it has something to show`() {
        val idle = TargetState(bar)
        val listed = listOf(playingTv(), idle)

        assertEquals(tv, listed.barFor("lg-tv")?.target)
        assertNull("an idle selected device has nothing to show, so the phone's own bar stays", listed.barFor("bar"))
        assertNull("the phone selected means no remote bar", listed.barFor(null))
        assertNull("a selected device that is not listed shows nothing", listed.barFor("gone"))
    }

    @Test
    fun `the bar decision says why, with the same four reasons the dock and the lock screen both log`() {
        val listed = listOf(playingTv(), TargetState(bar))

        assertEquals("LG TV is chosen and has 1 queued, at=0", listed.barDecision("lg-tv").why)
        assertEquals(tv, listed.barDecision("lg-tv").state?.target)
        assertEquals("this phone is the chosen device", listed.barDecision(null).why)
        assertEquals("gone is chosen but the Pi does not list it", listed.barDecision("gone").why)
        assertEquals("bar is chosen but has nothing playing or queued to show", listed.barDecision("bar").why)
        assertNull(listed.barDecision("bar").state)
    }

    @Test
    fun `a device that is on but holds no link is ready, not linked`() {
        assertEquals(TargetConnection.READY, TargetState(tv).connection)
    }

    @Test
    fun `a held link, a keep-awake that is keeping it, or playing all read as linked`() {
        assertEquals(TargetConnection.LINKED, TargetState(bar, linked = true).connection)
        assertEquals(TargetConnection.LINKED, TargetState(bar, keepAwake = KeepAwake.KEPT).connection)
        assertEquals(TargetConnection.LINKED, playingTv().connection)
    }

    @Test
    fun `power, a failing reconnect and a problem each win over a link, in that order`() {
        assertEquals(TargetConnection.ASLEEP, TargetState(tv, power = TargetPower.ASLEEP, linked = true).connection)
        assertEquals(TargetConnection.WAKING, TargetState(tv, power = TargetPower.WAKING).connection)
        assertEquals(
            TargetConnection.RECONNECTING,
            TargetState(bar, keepAwake = KeepAwake.RECONNECTING, linked = true).connection,
        )
        val trouble = TargetTrouble(TroubleKind.NOT_CONNECTED)
        assertEquals(TargetConnection.PROBLEM, TargetState(bar, trouble = trouble, linked = true).connection)
        assertEquals(TargetConnection.PROBLEM, TargetState(bar, problem = "plain Pi text").connection)
    }

    @Test
    fun `every kind of device has a glyph of its own, so two chips are never told apart by their words alone`() {
        val glyphs = TargetKind.entries.map { it.label.substringBefore(' ') }

        assertEquals(glyphs.toString(), TargetKind.entries.size, glyphs.distinct().size)
    }
}
