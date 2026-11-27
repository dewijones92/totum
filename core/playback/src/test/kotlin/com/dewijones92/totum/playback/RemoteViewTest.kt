package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteViewTest {

    private fun item(index: Int) = RemoteItem(index, "id$index", "Title $index", "Author", "https://art/$index.jpg")

    private fun facts(
        previous: RemoteItem? = item(0),
        next: RemoteItem? = item(2),
        playing: Boolean = true,
        starting: Boolean = false,
        durationMs: Long? = 600_000,
        positionMs: Long = 125_000,
    ) = RemoteFacts(
        deviceId = "lg-tv",
        deviceLabel = "📺 LG TV",
        statusLine = "📺 LG TV · ▶️ Playing",
        colour = 0xFF0089B0L,
        previous = previous,
        current = item(1),
        next = next,
        playing = playing,
        starting = starting,
        positionMs = positionMs,
        durationMs = durationMs,
    )

    @Test
    fun `a playing device is a ready player that wants to play, with the previous, current and next items`() {
        val view = RemoteView.of(facts())

        assertEquals(listOf("id0", "id1", "id2"), view.items.map { it.id })
        assertEquals(1, view.currentIndex)
        assertTrue(view.playWhenReady)
        assertEquals(Player.STATE_READY, view.playbackState)
        assertEquals(125_000L, view.positionMs)
        assertEquals(600_000L * 1_000L, view.durationUs)
    }

    @Test
    fun `a paused device is ready but does not want to play`() {
        val view = RemoteView.of(facts(playing = false))

        assertFalse(view.playWhenReady)
        assertEquals(Player.STATE_READY, view.playbackState)
    }

    @Test
    fun `a device that is still starting is buffering, wants to play and cannot be seeked`() {
        val view = RemoteView.of(facts(playing = false, starting = true))

        assertTrue(view.playWhenReady)
        assertEquals(Player.STATE_BUFFERING, view.playbackState)
        assertFalse(view.seekable)
        assertFalse(Player.COMMAND_SEEK_BACK in view.commands)
    }

    @Test
    fun `nothing to show is an idle, empty player with no current item`() {
        val view = RemoteView.of(null)

        assertEquals(Player.STATE_IDLE, view.playbackState)
        assertTrue(view.items.isEmpty())
        assertEquals(C.INDEX_UNSET, view.currentIndex)
    }

    @Test
    fun `with no previous item the current one is first, and previous is not offered`() {
        val view = RemoteView.of(facts(previous = null))

        assertEquals(listOf("id1", "id2"), view.items.map { it.id })
        assertEquals(0, view.currentIndex)
        assertFalse(Player.COMMAND_SEEK_TO_PREVIOUS in view.commands)
        assertFalse(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM in view.commands)
        assertTrue(Player.COMMAND_SEEK_TO_NEXT in view.commands)
    }

    @Test
    fun `with no next item next is not offered`() {
        val view = RemoteView.of(facts(next = null))

        assertFalse(Player.COMMAND_SEEK_TO_NEXT in view.commands)
        assertFalse(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM in view.commands)
        assertTrue(Player.COMMAND_SEEK_TO_PREVIOUS in view.commands)
    }

    @Test
    fun `an item with no length is live or unknown, so back 10 and forward 30 are not offered`() {
        val view = RemoteView.of(facts(durationMs = null))

        assertEquals(C.TIME_UNSET, view.durationUs)
        assertFalse(view.seekable)
        assertFalse(Player.COMMAND_SEEK_BACK in view.commands)
        assertFalse(Player.COMMAND_SEEK_FORWARD in view.commands)
        assertTrue("play and pause are always there", Player.COMMAND_PLAY_PAUSE in view.commands)
    }

    @Test
    fun `a known length offers play and pause, back, forward and seeking`() {
        val commands = RemoteView.of(facts()).commands

        assertTrue(
            commands.containsAll(
                listOf(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                ),
            ),
        )
    }

    @Test
    fun `a negative position from the Pi is shown as the start`() {
        assertEquals(0L, RemoteView.of(facts(positionMs = -5)).positionMs)
    }

    @Test
    fun `a zero length is no length`() {
        assertNull(RemoteView.of(facts(durationMs = 0)).durationMs)
    }
}

class RemoteSeeksTest {

    @Test
    fun `a jump to a later item is next`() {
        assertEquals(RemoteCommand.Next, RemoteSeeks.commandFor(2, 1, C.TIME_UNSET, 600_000))
    }

    @Test
    fun `a jump to an earlier item is previous`() {
        assertEquals(RemoteCommand.Previous, RemoteSeeks.commandFor(0, 1, C.TIME_UNSET, 600_000))
    }

    @Test
    fun `a seek inside the current item is a seek to that position`() {
        assertEquals(RemoteCommand.SeekTo(115_000), RemoteSeeks.commandFor(1, 1, 115_000, 600_000))
    }

    @Test
    fun `a seek is kept inside the item, so forward 30 near the end cannot pass the length`() {
        assertEquals(RemoteCommand.SeekTo(600_000), RemoteSeeks.commandFor(1, 1, 630_000, 600_000))
        assertEquals(RemoteCommand.SeekTo(0), RemoteSeeks.commandFor(1, 1, -4_000, 600_000))
    }

    @Test
    fun `with no known length a seek is only kept above zero`() {
        assertEquals(RemoteCommand.SeekTo(9_000_000), RemoteSeeks.commandFor(0, 0, 9_000_000, null))
    }
}

class RemoteSessionPlanTest {

    private fun item(index: Int, id: String = "id$index") = RemoteItem(index, id, "T", null, null)

    private fun facts(device: String = "lg-tv", current: RemoteItem = item(1), playing: Boolean = true) = RemoteFacts(
        deviceId = device,
        deviceLabel = "📺 $device",
        statusLine = "line",
        colour = 0xFF0089B0L,
        previous = null,
        current = current,
        next = null,
        playing = playing,
        starting = false,
        positionMs = 0,
        durationMs = 1_000,
    )

    @Test
    fun `a device with something to show, after none, opens the second session`() {
        assertTrue(RemoteSessionPlan.decide(null, facts()) is RemoteChange.Started)
    }

    @Test
    fun `a device with nothing left to show closes it, so the phone's own session is the only one`() {
        assertTrue(RemoteSessionPlan.decide(facts(), null) is RemoteChange.Ended)
    }

    @Test
    fun `nothing before and nothing after is nothing to do`() {
        assertEquals(RemoteChange.NothingToShow, RemoteSessionPlan.decide(null, null))
    }

    @Test
    fun `another device taking over is a switch, whatever it is playing`() {
        assertTrue(RemoteSessionPlan.decide(facts("lg-tv"), facts("soundbar")) is RemoteChange.SwitchedDevice)
    }

    @Test
    fun `the same device moving on to another item is a new item`() {
        assertTrue(RemoteSessionPlan.decide(facts(), facts(current = item(2))) is RemoteChange.NewItem)
    }

    @Test
    fun `the same item at another queue position is a new item too, so a repeated title is not missed`() {
        assertTrue(RemoteSessionPlan.decide(facts(), facts(current = item(2, id = "id1"))) is RemoteChange.NewItem)
    }

    @Test
    fun `the same item playing or pausing is only an update`() {
        assertTrue(RemoteSessionPlan.decide(facts(playing = true), facts(playing = false)) is RemoteChange.Updated)
    }

    @Test
    fun `every change says what happened and to which device, for the log`() {
        val line = RemoteSessionPlan.decide(null, facts()).line

        assertTrue(line, "📺 lg-tv" in line)
    }
}
