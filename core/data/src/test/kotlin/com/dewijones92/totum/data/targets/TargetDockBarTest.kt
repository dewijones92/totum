package com.dewijones92.totum.data.targets

import com.dewijones92.totum.data.backup.BackupItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TargetDockBarTest {

    private val item = BackupItem("a", "feed", "Toy Story", contentKind = "STANDARD", playbackType = "VIDEO")
    private val tv = Target("lg-tv", "LG TV", TargetKind.TV)
    private val bar = Target("bar", "Soundbar", TargetKind.SPEAKER)

    private fun playingTv() = TargetState(tv, entries = listOf(TargetEntry(item)), at = 0, playing = true)

    @Test
    fun `on any tab but the Queue the selected device that has something to show owns the bar`() {
        val listed = listOf(playingTv(), TargetState(bar))

        val chosen = listed.dockBarFor("lg-tv", onQueueTab = false)

        assertEquals(DockBar.Remote(listed.first()), chosen)
    }

    @Test
    fun `on the Queue tab the remote page already carries the same controls, so no bar is drawn at all`() {
        val listed = listOf(playingTv(), TargetState(bar))

        assertSame(DockBar.Hidden, listed.dockBarFor("lg-tv", onQueueTab = true))
    }

    @Test
    fun `the phone's own bar stays when nothing remote has anything to show, on every tab`() {
        val listed = listOf(playingTv(), TargetState(bar))

        assertSame(DockBar.Phone, listed.dockBarFor("bar", onQueueTab = false))
        assertSame(DockBar.Phone, listed.dockBarFor("bar", onQueueTab = true))
        assertSame(DockBar.Phone, listed.dockBarFor(null, onQueueTab = true))
        assertSame(DockBar.Phone, listed.dockBarFor("gone", onQueueTab = false))
    }

    @Test
    fun `a device says what it is doing in words that start with the state`() {
        assertEquals("▶️ Toy Story", playingTv().doingLine("Toy Story"))
        assertEquals("⏸️ Toy Story", playingTv().copy(playing = false).doingLine("Toy Story"))
        assertEquals("⏳ Toy Story", playingTv().copy(playing = false, starting = true).doingLine("Toy Story"))
        assertEquals("⚪ Idle", TargetState(bar).doingLine(null))
    }

    @Test
    fun `a title that is not known still says the state`() {
        assertEquals("▶️ Playing", playingTv().doingLine(null))
        assertEquals("⏸️ Paused", playingTv().copy(playing = false).doingLine("  "))
    }

    @Test
    fun `an asleep device with a saved queue is idle, not paused`() {
        val asleep = playingTv().copy(playing = false, power = TargetPower.ASLEEP)

        assertEquals(Doing.IDLE, asleep.doing)
        assertEquals("⚪ Idle", asleep.doingLine("Toy Story"))
    }

    @Test
    fun `a device waking to start something is starting even while it is asleep`() {
        val waking = playingTv().copy(playing = false, power = TargetPower.WAKING, starting = true)

        assertEquals(Doing.STARTING, waking.doing)
    }
}
