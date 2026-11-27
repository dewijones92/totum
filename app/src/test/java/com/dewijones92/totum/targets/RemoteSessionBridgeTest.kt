package com.dewijones92.totum.targets

import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.playback.RemoteCommand
import com.dewijones92.totum.playback.RemoteFacts
import com.dewijones92.totum.playback.RemoteSessionHost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionBridgeTest {

    private class FakeApi : TargetsApi {
        var targets: List<TargetState> = emptyList()
        var reads = 0
        val controlled = mutableListOf<TargetControl>()
        var controlAnswer: HomeAnswer<TargetState> = HomeAnswer.No("unused")

        override suspend fun targets(): HomeAnswer<TargetsAnswer> {
            reads++
            return HomeAnswer.Ok(TargetsAnswer(targets))
        }

        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")

        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> {
            controlled += control
            return controlAnswer
        }
    }

    private val api = FakeApi()
    private val scope = TestScope(UnconfinedTestDispatcher())
    private var now = 0L
    private val repository = TargetsRepository(
        api,
        { Requester("me", "My phone") },
        InMemoryTargetColours(),
        scope,
        playOn = InMemoryPlayOn(),
        nowMs = { now },
    )
    private val published = mutableListOf<RemoteFacts?>()

    private val lg = Target("lg-tv", "LG TV", TargetKind.TV)

    private fun entry(id: String) = TargetEntry(
        BackupItem(
            id,
            "feed",
            "Episode $id",
            mediaUrl = "https://example.com/$id.mp3",
            contentKind = "STANDARD",
            playbackType = "AUDIO",
        ),
    )

    private fun playing(playing: Boolean = true, at: Int = 0) =
        TargetState(lg, entries = listOf(entry("a"), entry("b")), at = at, playing = playing, durationMs = 600_000)

    private fun bridge() = RemoteSessionBridge(repository, scope, { label, _ -> label }, { published += it }).also {
        it.start()
    }

    @Test
    fun `a selected device with something playing is published to the lock screen session with its colour`() = runTest {
        api.targets = listOf(playing())
        repository.refresh("test")
        repository.select("lg-tv")

        bridge()

        val shown = checkNotNull(published.last())
        assertEquals("lg-tv", shown.deviceId)
        assertEquals("a", shown.current.id)
        assertEquals("b", shown.next?.id)
        assertEquals(repository.colours.value.getValue("lg-tv"), shown.colour)
    }

    @Test
    fun `choosing the phone takes the lock screen session away, so only the phone's own remains`() = runTest {
        api.targets = listOf(playing())
        repository.refresh("test")
        repository.select("lg-tv")
        bridge()

        repository.select(null)

        assertNull(published.last())
    }

    @Test
    fun `an idle selected device is not published, as the dock bar does not show it either`() = runTest {
        api.targets = listOf(TargetState(lg))
        repository.refresh("test")
        repository.select("lg-tv")

        bridge()

        assertTrue(published.all { it == null })
    }

    @Test
    fun `a command from the lock screen goes to the shown device as the same action the dock bar sends`() = runTest {
        api.targets = listOf(playing())
        repository.refresh("test")
        repository.select("lg-tv")
        bridge()

        RemoteSessionHost.commands.send(RemoteCommand.Pause)
        RemoteSessionHost.commands.send(RemoteCommand.SeekTo(155_000))

        assertEquals(listOf(TargetAction.PAUSE, TargetAction.SEEK), api.controlled.map { it.action })
        assertEquals(155_000L, api.controlled.last().positionMs)
    }

    @Test
    fun `a command with no device shown is ignored rather than sent to a guess`() = runTest {
        bridge()

        RemoteSessionHost.commands.send(RemoteCommand.Play)

        assertTrue(api.controlled.isEmpty())
    }

    @Test
    fun `while the screen reads every few seconds the lock screen adds no read of its own`() = runTest {
        api.targets = listOf(playing())
        repository.refresh("test")
        repository.select("lg-tv")
        bridge()
        val before = api.reads

        repeat(SCREEN_READS) {
            now += 3_000
            scope.advanceTimeBy(3_000)
            repository.refresh("the screen")
        }

        assertEquals("only the screen's own ${SCREEN_READS} reads", before + SCREEN_READS, api.reads)
    }

    @Test
    fun `with the screen off the lock screen reads for itself every ten seconds while playing`() = runTest {
        api.targets = listOf(playing())
        repository.refresh("test")
        repository.select("lg-tv")
        bridge()
        val before = api.reads

        now += 10_001
        scope.advanceTimeBy(10_001)

        assertEquals(before + 1, api.reads)
    }

    @Test
    fun `playing is read every ten seconds and paused every thirty, never faster than the dock bar`() {
        assertEquals(10_000L, RemotePolling.everyMs(factsOf(playing = true)))
        assertEquals(30_000L, RemotePolling.everyMs(factsOf(playing = false)))
        assertEquals(10_000L, RemotePolling.everyMs(factsOf(playing = false, starting = true)))
        assertTrue("slower than the 3s the dock bar reads at while playing", RemotePolling.PLAYING_MS > 3_000)
    }

    @Test
    fun `a read the screen made a moment ago is fresh enough, an old one is not`() = runTest {
        api.targets = listOf(playing())
        repository.refresh("test")

        now += 7_000
        assertEquals(Polled.FRESH_ENOUGH, repository.refreshIfOlderThan(RemotePolling.freshEnoughMs(10_000), "x"))

        now += 2_000
        assertEquals(Polled.READ, repository.refreshIfOlderThan(RemotePolling.freshEnoughMs(10_000), "x"))
    }

    @Test
    fun `an answer to a command counts as a read, so the lock screen does not read straight after`() = runTest {
        api.targets = listOf(playing())
        repository.refresh("test")
        now += 9_000

        api.controlAnswer = HomeAnswer.Ok(playing(playing = false))
        repository.control("lg-tv", TargetControl(TargetAction.PAUSE))
        now += 100

        assertEquals(Polled.FRESH_ENOUGH, repository.refreshIfOlderThan(RemotePolling.freshEnoughMs(10_000), "x"))
    }

    private fun factsOf(playing: Boolean, starting: Boolean = false) = RemoteFacts(
        deviceId = "lg-tv",
        deviceLabel = "📺 LG TV",
        statusLine = "",
        colour = 0,
        previous = null,
        current = com.dewijones92.totum.playback.RemoteItem(0, "a", "A", null, null),
        next = null,
        playing = playing,
        starting = starting,
        positionMs = 0,
        durationMs = 1_000,
    )

    private companion object {
        const val SCREEN_READS = 8
    }
}
