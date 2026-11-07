package com.dewijones92.totum.trip

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripModeTest {

    private val group = QueueGroup("feed:show", "A show")
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun stopWatchers() = scopes.forEach { it.cancel() }

    private fun tripTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            scopes.forEach { it.cancel() }
        }
    }

    private fun entry(id: String) = QueueEntry(
        PlayableItem(
            MediaItem(
                MediaItemId(id),
                SourceId("feed"),
                id,
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
            ),
            PlayHandle.Podcast(),
        ),
        group,
    )

    private class Rig(scope: CoroutineScope, queue: List<QueueEntry>, val now: MutableStateFlow<Long>) {
        val queueState = MutableStateFlow(QueueSnapshot(queue))
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val cancelled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var unmetered = true
        var batchRunning = false
        var states: Map<MediaItemId, DownloadState> = emptyMap()
        var piFiles: List<PiFile> = emptyList()
        var home: Set<String> = emptySet()
        val fetched = mutableListOf<List<String>>()
        val copied = mutableListOf<String>()
        val store = InMemoryTripStore()

        val trip = TripMode(
            store = store,
            ports = TripPorts(
                queue = queueState,
                changes = changes,
                states = { states },
                unmetered = { unmetered },
                finished = { false },
                fetchesAudioOnly = { true },
                atHome = { it.item.id.value in home },
                choice = { DownloadChoice.Sound },
                piFiles = { piFiles },
                copyFromPi = { copied += it.value },
                freeBytes = { null },
                fetch = { _, items, _ -> fetched += items.map { it.item.id.value } },
                batchRunning = { batchRunning },
                batchCancelled = cancelled,
                batchTitle = "the trip",
            ),
            scope = scope,
            clock = { now.value },
        )
    }

    private fun TestScope.rig(vararg ids: String): Rig {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        return Rig(scope, ids.map(::entry), MutableStateFlow(START))
    }

    @Test
    fun `arming asks for the next few of each group and shows progress`() = tripTest {
        val rig = rig("a", "b", "c", "d")

        rig.trip.arm(START + HOUR * 2, perGroup = 3, why = "test")
        runCurrent()

        assertEquals(listOf(listOf("a", "b", "c")), rig.fetched)
        val status = rig.trip.status.value as TripStatus.On
        assertEquals(START + HOUR * 2, status.departureAtMs)
        assertEquals(3, status.progress?.wanted)
        assertEquals(3, status.progress?.fetching)
        assertFalse(status.waitingForWifi)
        assertTrue(rig.store.read().armed)
    }

    @Test
    fun `on mobile data it waits and fetches nothing`() = tripTest {
        val rig = rig("a", "b")
        rig.unmetered = false

        rig.trip.arm(START + HOUR, perGroup = 2, why = "test")
        runCurrent()

        assertTrue(rig.fetched.isEmpty())
        assertTrue((rig.trip.status.value as TripStatus.On).waitingForWifi)
    }

    @Test
    fun `a change in the queue is looked at again and only what is new is asked for`() = tripTest {
        val rig = rig("a", "b")
        rig.trip.arm(START + HOUR, perGroup = 2, why = "test")
        runCurrent()
        rig.states = mapOf(MediaItemId("a") to DownloadState.Downloaded("/x/a.mp3"))

        rig.changes.tryEmit(Unit)
        runCurrent()

        assertEquals(listOf(listOf("a", "b"), listOf("b")), rig.fetched)
        assertEquals(1, (rig.trip.status.value as TripStatus.On).progress?.onPhone)
    }

    @Test
    fun `a running batch is not given a second one`() = tripTest {
        val rig = rig("a", "b")
        rig.batchRunning = true

        rig.trip.arm(START + HOUR, perGroup = 2, why = "test")
        runCurrent()

        assertTrue(rig.fetched.isEmpty())
    }

    @Test
    fun `a queued item the Pi has, and other files the Pi has ready, come through the Pi copy path`() = tripTest {
        val rig = rig("a", "b")
        rig.home = setOf("a")
        rig.piFiles = listOf(PiFile(MediaItemId("a"), "a", 10), PiFile(MediaItemId("z"), "z", 10))

        rig.trip.arm(START + HOUR, perGroup = 2, why = "test")
        runCurrent()

        assertEquals(listOf(listOf("b")), rig.fetched)
        assertEquals(listOf("a", "z"), rig.copied)
        assertEquals(2, (rig.trip.status.value as TripStatus.On).progress?.fromPi)
    }

    @Test
    fun `the departure time ends trip mode`() = tripTest {
        val rig = rig("a")
        rig.trip.arm(START + HOUR, perGroup = 2, why = "test")
        runCurrent()

        rig.now.value = START + HOUR
        val answer = rig.trip.passNow("test")

        assertEquals("departure time came", answer)
        assertEquals(TripStatus.Off, rig.trip.status.value)
        assertFalse(rig.store.read().armed)
    }

    @Test
    fun `cancelling the batch from the strip switches trip mode off`() = tripTest {
        val rig = rig("a")
        rig.trip.arm(START + HOUR, perGroup = 2, why = "test")
        runCurrent()

        rig.cancelled.tryEmit(Unit)
        runCurrent()

        assertEquals(TripStatus.Off, rig.trip.status.value)
        assertFalse(rig.store.read().armed)
    }

    @Test
    fun `stopping it by hand clears it, and a second stop does nothing`() = tripTest {
        val rig = rig("a")
        rig.trip.arm(START + HOUR, perGroup = 2, why = "test")
        runCurrent()

        rig.trip.disarm("test")
        rig.trip.disarm("again")

        assertEquals(TripStatus.Off, rig.trip.status.value)
    }

    @Test
    fun `a time in the past is refused and the count is kept in range`() = tripTest {
        val rig = rig("a")

        rig.trip.arm(START - 1, perGroup = 3, why = "test")
        assertEquals(TripStatus.Off, rig.trip.status.value)

        rig.trip.arm(START + HOUR, perGroup = 500, why = "test")
        assertEquals(TripSettings.MAX_PER_GROUP, rig.trip.settings.value.perGroup)
    }

    @Test
    fun `a trip still ahead resumes at launch and one that has passed is dropped`() = tripTest {
        val resumed = rig("a")
        resumed.store.write(TripSettings(START + HOUR, 4))
        val fresh = TripMode(
            resumed.store,
            resumedPorts(resumed),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)).also { scopes += it },
            { START }
        )
        fresh.start()
        runCurrent()
        assertTrue(fresh.status.value is TripStatus.On)

        val stale = rig("a")
        stale.store.write(TripSettings(START - HOUR, 4))
        val gone = TripMode(
            stale.store,
            resumedPorts(stale),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)).also { scopes += it },
            { START }
        )
        gone.start()
        assertEquals(TripStatus.Off, gone.status.value)
        assertFalse(stale.store.read().armed)
    }

    private fun resumedPorts(rig: Rig) = TripPorts(
        queue = rig.queueState,
        changes = rig.changes,
        states = { emptyMap() },
        unmetered = { true },
        finished = { false },
        fetchesAudioOnly = { true },
        atHome = { false },
        choice = { DownloadChoice.Sound },
        piFiles = { emptyList() },
        copyFromPi = {},
        freeBytes = { null },
        fetch = { _, _, _ -> },
        batchRunning = { false },
        batchCancelled = rig.cancelled,
        batchTitle = "the trip",
    )

    @Test
    fun `the settings survive a round trip through their codec`() {
        val settings = TripSettings(1_234L, 7)

        assertEquals(settings, TripCodec.decode(TripCodec.encode(settings)))
    }

    private companion object {
        const val START = 1_000_000L
        const val HOUR = 3_600_000L
    }
}
