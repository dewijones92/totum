package com.dewijones92.totum.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NoTracksWatchTest {

    private var itemId = "torrent:abc:7"
    private var remote = false
    private var state = Player.STATE_READY
    private var playing = false
    private var positionMs = 0L
    private val events = mutableListOf<PlaybackEvent.CouldNotPlay>()
    private val pending = mutableListOf<() -> Unit>()

    private val player: Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentTracks" -> Tracks.EMPTY
                "getCurrentMediaItem" -> MediaItem.Builder().setMediaId(itemId).build()
                "getPlaybackState" -> state
                "isCurrentMediaItemSeekable" -> false
                "getCurrentPosition" -> positionMs
                "isPlaying" -> playing
                "getAvailableCommands" -> Player.Commands.EMPTY
                "getDeviceInfo" -> DeviceInfo.Builder(
                    if (remote) DeviceInfo.PLAYBACK_TYPE_REMOTE else DeviceInfo.PLAYBACK_TYPE_LOCAL,
                ).build()
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    else -> null
                }
            }
        } as Player

    private val watch = NoTracksWatch({ player }, { _, action -> pending += action }) { events += it }

    private fun settle() {
        pending.toList().also { pending.clear() }.forEach { it() }
    }

    @Test
    fun `a file that is ready with no tracks is reported as having its track list at the end, with what was found`() {
        watch.onPlaybackStateChanged(Player.STATE_READY)
        settle()

        val event = events.single()
        assertEquals("torrent:abc:7", event.itemId.value)
        val problem = event.problem as PlaybackProblem.NoTrackList
        assertEquals("0 track(s): none; not seekable", problem.summary)
    }

    @Test
    fun `a file that is playing with its position moving is never told as having no tracks`() {
        playing = true
        positionMs = 5_200L

        watch.onPlaybackStateChanged(Player.STATE_READY)
        positionMs = 7_700L
        settle()

        assertTrue(events.toString(), events.isEmpty())
    }

    @Test
    fun `a player that claims to be playing but whose position is stuck is still told`() {
        playing = true
        positionMs = 5_200L

        watch.onPlaybackStateChanged(Player.STATE_READY)
        settle()

        assertEquals(1, events.size)
    }

    @Test
    fun `a position that moves while the player is not playing is still told`() {
        playing = false
        positionMs = 0L

        watch.onPlaybackStateChanged(Player.STATE_READY)
        positionMs = 3_000L
        settle()

        assertEquals(1, events.size)
    }

    @Test
    fun `it is said once for the item, however many times it is noticed`() {
        repeat(3) {
            watch.onPlaybackStateChanged(Player.STATE_READY)
            settle()
        }

        assertEquals(1, events.size)
    }

    @Test
    fun `the next item is judged afresh`() {
        watch.onPlaybackStateChanged(Player.STATE_READY)
        settle()
        itemId = "torrent:abc:8"
        watch.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        watch.onPlaybackStateChanged(Player.STATE_READY)
        settle()

        assertEquals(listOf("torrent:abc:7", "torrent:abc:8"), events.map { it.itemId.value })
    }

    @Test
    fun `nothing is said if the player moves on or stops being ready before the recheck`() {
        watch.onPlaybackStateChanged(Player.STATE_READY)
        state = Player.STATE_BUFFERING
        settle()
        state = Player.STATE_READY
        watch.onPlaybackStateChanged(Player.STATE_READY)
        itemId = "other"
        settle()

        assertTrue(events.toString(), events.isEmpty())
    }

    @Test
    fun `a receiver's tracks are not the phone's, so nothing is said while casting`() {
        remote = true

        watch.onPlaybackStateChanged(Player.STATE_READY)
        settle()

        assertTrue(events.isEmpty())
    }
}
