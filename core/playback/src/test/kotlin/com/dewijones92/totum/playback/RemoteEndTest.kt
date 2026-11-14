package com.dewijones92.totum.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.dewijones92.totum.domain.MediaItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class RemoteEndTest {

    private var clock = 0L
    private var standIn: String? = null
    private val ended = mutableListOf<PlaybackEvent.Ended>()

    private class Facts(
        var item: MediaItem? = null,
        var remote: Boolean = true,
        var positionMs: Long = 0,
        var durationMs: Long = -1,
        var playing: Boolean = true,
        var itemCount: Int = 1,
    )

    private val facts = Facts()

    private val player: Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> facts.item
                "getDeviceInfo" -> DeviceInfo.Builder(
                    if (facts.remote) DeviceInfo.PLAYBACK_TYPE_REMOTE else DeviceInfo.PLAYBACK_TYPE_LOCAL,
                ).build()
                "getCurrentPosition" -> facts.positionMs
                "getDuration" -> facts.durationMs
                "isPlaying" -> facts.playing
                "getMediaItemCount" -> facts.itemCount
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Player

    private val remoteEnd = RemoteEnd(standIn = { standIn }, clock = { clock }).also { it.onEnded = ended::add }

    private fun eventsWithNoFlags(): Player.Events {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Player.Events::class.java) as Player.Events
    }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun sees(mediaId: String, positionMs: Long, durationMs: Long = 600_000, playing: Boolean = true) {
        facts.item = item(mediaId)
        facts.positionMs = positionMs
        facts.durationMs = durationMs
        facts.playing = playing
        remoteEnd.onEvents(player, eventsWithNoFlags())
    }

    @Test
    fun `an item the receiver names that leaves near its end is counted as ended`() {
        sees("a", 596_000)

        remoteEnd.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals(listOf(PlaybackEvent.Ended(MediaItemId("a"), 596_000, 600_000)), ended)
    }

    @Test
    fun `an item the receiver does not name is seeded from the stand-in, so its end is not missed`() {
        standIn = "totum-asked-for"
        sees("", 597_000)

        remoteEnd.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals(listOf("totum-asked-for"), ended.map { it.itemId.value })
    }

    @Test
    fun `an unnamed item with no stand-in cannot be seeded, so no end is claimed`() {
        standIn = null
        sees("", 597_000)

        remoteEnd.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertTrue(ended.isEmpty())
    }

    @Test
    fun `the receiver moving to another item it holds is a skip, not an end`() {
        sees("a", 596_000)

        remoteEnd.onMediaItemTransition(item("b"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertTrue(ended.isEmpty())
    }

    @Test
    fun `an item dropped early is not an end`() {
        sees("a", 30_000)

        remoteEnd.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertTrue(ended.isEmpty())
    }

    @Test
    fun `the phone's own player is never watched for a remote end`() {
        facts.remote = false
        sees("a", 596_000)

        remoteEnd.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertTrue(ended.isEmpty())
    }

    @Test
    fun `what was seen on the TV is forgotten once the player is the phone's again`() {
        sees("a", 596_000)
        facts.remote = false
        remoteEnd.onEvents(player, eventsWithNoFlags())
        facts.remote = true

        remoteEnd.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertTrue(ended.isEmpty())
    }

    @Test
    fun `a transition before the player has ever reported says nothing`() {
        remoteEnd.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertTrue(ended.isEmpty())
    }

    @Test
    fun `finished on the TV is answered from what was seen`() {
        sees("a", 597_000)

        assertEquals(true, remoteEnd.finishedOnTv("a", "b"))
        assertEquals(null, remoteEnd.finishedOnTv("never-seen", "b"))
    }

    @Test
    fun `a pause is carried by the phone's clock only while playing`() {
        sees("a", 100_000, playing = false)
        clock += 600_000

        remoteEnd.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertTrue(ended.isEmpty())
    }
}
