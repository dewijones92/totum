package com.dewijones92.totum.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.domain.ItemFace
import com.dewijones92.totum.domain.ItemShield
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.NoShield
import com.dewijones92.totum.playback.RemoteCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSessionFactsTest {

    private fun entry(id: String, title: String = "Episode $id") = TargetEntry(
        BackupItem(
            id,
            "feed",
            title,
            author = "Author $id",
            thumbnailUrl = "https://example.com/$id.jpg",
            mediaUrl = "https://example.com/$id.mp3",
            contentKind = "STANDARD",
            playbackType = "AUDIO",
        ),
    )

    private val lg = Target("lg-tv", "LG TV", TargetKind.TV)

    private fun state(
        at: Int = 1,
        playing: Boolean = true,
        starting: Boolean = false,
        durationMs: Long? = 600_000,
        power: TargetPower = TargetPower.ON,
    ) = TargetState(
        lg,
        entries = listOf(entry("a"), entry("b"), entry("c")),
        at = at,
        playing = playing,
        starting = starting,
        positionMs = 125_000,
        durationMs = durationMs,
        power = power,
    )

    private val line: (String, TargetState) -> String = { label, s ->
        "$label · ${if (s.starting) "⏳ Starting…" else if (s.playing) "▶️ Playing" else "⏸️ Paused"}"
    }

    private fun facts(state: TargetState, shield: ItemShield = NoShield) =
        checkNotNull(state.toRemoteFacts(0xFF0089B0L, line, shield))

    @Test
    fun `the device's title, author, art, position and length are what the lock screen gets`() {
        val shown = facts(state())

        assertEquals("lg-tv", shown.deviceId)
        assertEquals("📺 LG TV", shown.deviceLabel)
        assertEquals("📺 LG TV · ▶️ Playing", shown.statusLine)
        assertEquals(0xFF0089B0L, shown.colour)
        assertEquals("b", shown.current.id)
        assertEquals("Episode b", shown.current.title)
        assertEquals("Author b", shown.current.author)
        assertEquals("https://example.com/b.jpg", shown.current.artworkUrl)
        assertEquals(125_000L, shown.positionMs)
        assertEquals(600_000L, shown.durationMs)
        assertTrue(shown.playing)
    }

    @Test
    fun `the item before and after it are named with their queue positions`() {
        val shown = facts(state(at = 1))

        assertEquals(0 to "a", shown.previous?.let { it.queueIndex to it.id })
        assertEquals(2 to "c", shown.next?.let { it.queueIndex to it.id })
    }

    @Test
    fun `the first item has no previous and the last has no next`() {
        assertNull(facts(state(at = 0)).previous)
        assertNull(facts(state(at = 2)).next)
    }

    @Test
    fun `a device with nothing current gives nothing to show`() {
        assertNull(TargetState(lg).toRemoteFacts(0xFF0089B0L, line))
        assertNull(state(at = 9).toRemoteFacts(0xFF0089B0L, line))
    }

    @Test
    fun `a live item has no length, so the lock screen offers no seeking`() {
        assertNull(facts(state(durationMs = null)).durationMs)
        assertNull(facts(state().copy(live = true)).durationMs)
    }

    @Test
    fun `the status line comes from the same words as the dock bar, and says starting while it starts`() {
        assertEquals("📺 LG TV · ⏳ Starting…", facts(state(playing = false, starting = true)).statusLine)
        assertEquals("📺 LG TV · ⏸️ Paused", facts(state(playing = false)).statusLine)
        assertEquals("📺 LG TV 💤 · ▶️ Playing", facts(state(power = TargetPower.ASLEEP)).statusLine)
    }

    @Test
    fun `the spoiler shield decides the title and whether art is shown, as it does on every other surface`() {
        val shield = object : ItemShield {
            override fun face(item: MediaItem) =
                ItemFace("🙈 Title hidden", HttpUrl.of("https://example.com/real.jpg"), null, shielded = true)
        }

        val shown = facts(state(), shield)

        assertEquals("🙈 Title hidden", shown.current.title)
        assertNull("a shielded item shows no artwork", shown.current.artworkUrl)
        assertFalse("the real title must not be anywhere on the lock screen", "Episode" in shown.current.title)
        assertEquals("🙈 Title hidden", shown.previous?.title)
    }

    @Test
    fun `each player command becomes the target action the dock bar sends`() {
        assertEquals(TargetAction.PLAY, RemoteCommand.Play.toControl().action)
        assertEquals(TargetAction.PAUSE, RemoteCommand.Pause.toControl().action)
        assertEquals(TargetAction.NEXT, RemoteCommand.Next.toControl().action)
        assertEquals(TargetAction.PREVIOUS, RemoteCommand.Previous.toControl().action)
        RemoteCommand.SeekTo(155_000).toControl().let {
            assertEquals(TargetAction.SEEK, it.action)
            assertEquals(155_000L, it.positionMs)
        }
    }
}
