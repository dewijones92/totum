package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPlays
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TargetDeckJellyfinOnlyTest {

    private class JellyfinOnlyPlayer : TargetPlayer {
        val requests = mutableListOf<PlayRequest>()
        override val plays: TargetPlays get() = TargetPlays.JELLYFIN_ONLY
        override fun play(request: PlayRequest) {
            requests += request
        }
        override fun pause(paused: Boolean) = Unit
        override fun seek(positionMs: Long) = Unit
        override fun volume(percent: Int) = Unit
        override val volumePercent: Int? = null
        override fun stop() = Unit
        override val positionMs: Long? = 0
        override val durationMs: Long? = null
        override var onEnded: (failed: Boolean) -> Unit = {}
    }

    private val player = JellyfinOnlyPlayer()
    private val resolved = mutableListOf<String>()
    private val sounds = TargetSounds { item, _ ->
        resolved += item.item.id.value
        "https://sound/${item.item.id.value}"
    }
    private val dir = Files.createTempDirectory("jellyfin-only").toFile()
    private val deck = TargetDeck(
        Target("jellyfin-tv", "Living room Jellyfin", TargetKind.TV),
        player,
        ConnectingLink("living-room"),
        sounds,
        dir.resolve("jellyfin-tv.json"),
    )

    private fun film(id: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"): BackupItem = PlayableItem(
        MediaItem(MediaItemId("jellyfin:$id"), SourceId("jellyfin"), "Film $id", publishedAt = null, duration = null),
        PlayHandle.Podcast(audioUrl = HttpUrl.of("https://pi.invalid/jellyfin/stream/$id.mp4")),
    ).toBackup()

    @Test
    fun `a target that plays only Jellyfin says so in its state`() {
        assertEquals(TargetPlays.JELLYFIN_ONLY, deck.state().plays)
    }

    @Test
    fun `a Jellyfin film is played and its item id travels with the request`() = runBlocking {
        deck.enqueue(TargetQueueRequest(listOf(film()), QueueMode.REPLACE))

        assertEquals(listOf("jellyfin:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), player.requests.map { it.itemId })
        assertTrue(deck.state().playing)
    }

    @Test
    fun `a YouTube item sent to it is refused with the reason, and nothing is looked up or played`() = runBlocking {
        val result = deck.accept(TargetQueueRequest(listOf(podcastItem("dQw4w9WgXcQ")), QueueMode.PLAY))

        val refused = result as EnqueueResult.Refused
        assertTrue(refused.why, refused.why.contains("plays only films and episodes from the Jellyfin library"))
        assertTrue(refused.why, refused.why.contains("Living room Jellyfin"))
        assertTrue(player.requests.isEmpty())
        assertTrue(resolved.isEmpty())
        assertTrue(deck.state().entries.isEmpty())
    }

    @Test
    fun `a mix keeps the Jellyfin items and leaves the rest out`() = runBlocking {
        val kept = film("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        deck.enqueue(TargetQueueRequest(listOf(podcastItem("dQw4w9WgXcQ"), kept), QueueMode.REPLACE))

        assertEquals(listOf("jellyfin:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), deck.state().entries.map { it.item.itemId })
        assertEquals(listOf("jellyfin:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), player.requests.map { it.itemId })
    }

    @Test
    fun `an id that merely starts like Jellyfin's but is not a Jellyfin id is refused too`() = runBlocking {
        val result = deck.accept(TargetQueueRequest(listOf(podcastItem("jellyfin:not-an-id!")), QueueMode.PLAY))

        assertTrue(result is EnqueueResult.Refused)
    }
}
