package com.dewijones92.totum.ui.ask

import com.dewijones92.totum.data.ask.state.AppState
import com.dewijones92.totum.data.ask.state.AskStateWire
import com.dewijones92.totum.data.ask.state.StateJson
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.backup.BackupSubscription
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.StatEvent
import com.dewijones92.totum.stats.UseKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AskStateSourceTest {

    private val dispatcher = StandardTestDispatcher()

    private fun video(id: String, title: String) =
        MediaItem(MediaItemId(id), SourceId("c"), title, publishedAt = null, duration = null).asPlayable()

    private var queue = listOf(video("v1", "A video"), video("v2", "Another"))
    private var queueReads = 0
    private var stats: List<StatEvent> = emptyList()
    private var secrets: List<String> = emptyList()
    private var hosts: List<String> = emptyList()
    private var titleSeen = mutableListOf<String>()

    private fun source(
        backup: Backup = Backup(
            subscriptions = listOf(BackupSubscription("feed-1", "A Show", "https://feed.example/rss", "podcast")),
        ),
        trail: () -> Pair<List<PlayableItem>, Int> = { emptyList<PlayableItem>() to -1 },
    ) = AskStateSource(
        AskStatePorts(
            backup = { backup },
            queue = {
                queueReads++
                QueueSnapshot(queue.map { QueueEntry(it) })
            },
            downloaded = { emptyList() },
            records = { emptyList() },
            locked = { emptySet() },
            stats = { stats },
            trail = trail,
            channels = { emptyList() },
            appVersion = "9.9",
            starredName = "⭐ Starred",
            secrets = { secrets },
            hosts = { hosts },
            titleFor = { id, title, _ ->
                titleSeen += id
                if (id == "v2") "🙈 Title hidden" else title
            },
        ),
        now = { 1_793_000_000_000L },
        zone = { ZoneId.of("UTC") },
        work = dispatcher,
    )

    private fun stateOf(snapshot: AskStateSource.Snapshot): AppState =
        StateJson.decodeFromJsonElement(AppState.serializer(), AskStateWire.unpack(snapshot.packed).state)

    @Test
    fun `a snapshot packs the queue, the follows and the app version, and unpacks to the same thing`() =
        runTest(dispatcher) {
            val snapshot = source().snapshot()

            val state = stateOf(snapshot)
            assertEquals(listOf("A video", "🙈 Title hidden"), state.queue.items.map { it.title })
            assertEquals(listOf("A Show"), state.subscriptions.map { it.title })
            assertEquals("9.9", state.snapshot.appVersion)
            assertEquals("UTC", state.snapshot.timeZone)
            assertEquals(snapshot.built.json, AskStateWire.unpack(snapshot.packed).state)
            assertTrue(snapshot.id.startsWith("ask-"))
        }

    @Test
    fun `the title seam is asked for every queued item, so a shielded title never reaches the Pi`() =
        runTest(dispatcher) {
            val snapshot = source().snapshot()

            assertTrue(titleSeen.containsAll(listOf("v1", "v2")))
            assertFalse(AskStateWire.unpack(snapshot.packed).state.toString().contains("Another"))
        }

    @Test
    fun `a listen is a listen and every other usage event is an other stat of its own type`() =
        runTest(dispatcher) {
            stats = listOf(
                StatEvent.Listen(
                    at = 1_000, playId = "p", itemId = "v1", title = "A video", kind = ListenKind.VIDEO,
                    watched = true, heardMs = 40_000, durationMs = 100_000,
                ),
                StatEvent.AppUse(at = 2_000, what = UseKind.SEARCH, detail = "dragons"),
            )

            val state = stateOf(source().snapshot())

            assertEquals(listOf("A video"), state.listens.map { it.title })
            assertTrue(state.listens.single().counted)
            assertEquals(listOf("use:search"), state.otherStats.map { it.type })
        }

    @Test
    fun `an unlock goes into the pack with its time and never the app it opened first`() = runTest(dispatcher) {
        stats = listOf(StatEvent.PhoneUnlock(at = 5_000, firstApp = "com.whatsapp"))

        val text = AskStateWire.unpack(source().snapshot().packed).state.toString()
        val state = stateOf(source().snapshot())

        assertFalse(text, text.contains("com.whatsapp"))
        assertEquals(listOf("phone-unlock"), state.otherStats.map { it.type })
        assertEquals(5_000L, state.otherStats.single().atMs)
    }

    @Test
    fun `a secret and a host the service knows are taken out of the pack`() = runTest(dispatcher) {
        queue = listOf(video("v1", "Link https://pi.example/x?token=SECRET-TOKEN-123 and pi.example"))
        secrets = listOf("SECRET-TOKEN-123")
        hosts = listOf("pi.example")

        val text = AskStateWire.unpack(source().snapshot().packed).state.toString()

        assertFalse(text, text.contains("SECRET-TOKEN-123"))
        assertFalse(text, text.contains("pi.example"))
    }

    @Test
    fun `the items of the last snapshot are looked up again without gathering the state a second time`() =
        runTest(dispatcher) {
            val source = source()
            source.snapshot()
            val before = queueReads

            val found = source.resolve(listOf("v1", "v2"))

            assertEquals(before, queueReads)
            assertEquals(listOf("v1", "v2"), found.map { it.item.id.value })
        }

    @Test
    fun `an id the last snapshot lacked makes the state be gathered afresh, an unknown id is missing`() =
        runTest(dispatcher) {
            val source = source()
            source.snapshot()
            queue = queue + video("v3", "Added since")
            val before = queueReads

            val found = source.resolve(listOf("v3", "ghost"))

            assertNotEquals(before, queueReads)
            assertEquals(listOf("v3"), found.map { it.item.id.value })
        }

    @Test
    fun `the trail and its place are read from the port`() = runTest(dispatcher) {
        val source = source(trail = { listOf(video("v1", "A video")) to 0 })

        val state = stateOf(source.snapshot())

        assertEquals(listOf("A video"), state.trail.items.map { it.title })
        assertEquals(1, state.trail.position)
    }
}
