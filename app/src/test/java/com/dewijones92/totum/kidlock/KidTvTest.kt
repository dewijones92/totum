package com.dewijones92.totum.kidlock

import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.PinPlayed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KidTvTest {

    private val playlist = Pin.Playlist("PL1", "Peppa", null, music = false)
    private val device = MutableStateFlow<String?>(null)

    private class Rig(
        scope: CoroutineScope,
        val device: MutableStateFlow<String?>,
        val playlist: Pin,
        choice: KidTvChoice,
    ) {
        val lock = KidLock()
        val played = mutableListOf<Pin>()
        var connection: TvConnection = TvConnection.Connected("Living room TV")
        var playResult: PinPlayed = PinPlayed.Started(showsPicture = true)
        val connectCalls = mutableListOf<String>()
        var pauses = 0
        var endedCasts = 0
        val store = InMemoryKidTvStore(choice)
        val pins = mutableListOf<Pin>(playlist)

        val kidTv = KidTv(
            store = store,
            pins = { pins.toList() },
            ports = KidTvPorts(
                connector = object : TvConnector {
                    override fun scan(): Flow<List<String>> = flowOf(listOf("Living room TV"))

                    override suspend fun connect(tvName: String): TvConnection {
                        connectCalls += tvName
                        (connection as? TvConnection.Connected)?.let { device.value = it.device }
                        return connection
                    }
                },
                castDevice = device,
                play = { pin ->
                    played += pin
                    playResult
                },
                pause = { pauses++ },
                endCast = {
                    endedCasts++
                    device.value = null
                },
                startingLabel = { title, tv -> "Starting $title on $tv" },
            ),
            lock = lock,
            operations = Operations(),
            scope = scope,
        )
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun stopWatchers() = scopes.forEach { it.cancel() }

    private fun TestScope.rig(choice: KidTvChoice = KidTvChoice(playlist.key, "Living room TV")): Rig {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        return Rig(scope, device, playlist, choice)
    }

    @Test
    fun `nothing chosen yet means not set up and a tap starts nothing`() = runTest {
        val rig = rig(KidTvChoice())

        rig.kidTv.tap("test")
        advanceUntilIdle()

        assertEquals(KidTvStatus.NotSetUp, rig.kidTv.status.value)
        assertFalse(rig.lock.modeOn.value)
        assertTrue(rig.connectCalls.isEmpty())
    }

    @Test
    fun `a tap connects, switches kid mode on before playing, plays the playlist and says it is on`() = runTest {
        val rig = rig()
        assertEquals(KidTvStatus.Ready, rig.kidTv.status.value)

        rig.kidTv.tap("test")
        advanceUntilIdle()

        assertEquals(listOf("Living room TV"), rig.connectCalls)
        assertEquals(listOf<Pin>(playlist), rig.played)
        assertEquals(KidTvStatus.On("Living room TV", "Peppa"), rig.kidTv.status.value)
        assertTrue(rig.lock.modeOn.value)
        assertFalse("the phone itself is not locked", rig.lock.locked.value)
        assertEquals("Living room TV", rig.lock.tv.value)
    }

    @Test
    fun `already casting to the chosen TV skips the connection`() = runTest {
        val rig = rig()
        device.value = "living room tv"

        rig.kidTv.tap("test")
        advanceUntilIdle()

        assertTrue(rig.connectCalls.isEmpty())
        assertEquals(listOf<Pin>(playlist), rig.played)
    }

    @Test
    fun `a TV that is not found fails with what was seen and never turns kid mode on`() = runTest {
        val rig = rig()
        rig.connection = TvConnection.NotFound(listOf("Bedroom speaker"))

        rig.kidTv.tap("test")
        advanceUntilIdle()

        val failed = rig.kidTv.status.value as KidTvStatus.Failed
        assertEquals(KidTvFailure.TvNotFound("Living room TV", listOf("Bedroom speaker")), failed.failure)
        assertFalse(rig.lock.modeOn.value)
        assertTrue(rig.played.isEmpty())
    }

    @Test
    fun `no local network access is its own failure`() = runTest {
        val rig = rig()
        rig.connection = TvConnection.NeedsLocalNetwork

        rig.kidTv.tap("test")
        advanceUntilIdle()

        assertEquals(KidTvStatus.Failed(KidTvFailure.NeedsLocalNetwork), rig.kidTv.status.value)
    }

    @Test
    fun `a playlist that will not start turns kid mode back off`() = runTest {
        val rig = rig()
        rig.playResult = PinPlayed.Failed("nothing in it to play")

        rig.kidTv.tap("test")
        advanceUntilIdle()

        assertEquals(
            KidTvStatus.Failed(KidTvFailure.PlayFailed("Peppa", "nothing in it to play")),
            rig.kidTv.status.value,
        )
        assertFalse(rig.lock.modeOn.value)
        assertNull(rig.lock.tv.value)
    }

    @Test
    fun `a chosen playlist that is no longer pinned is reported`() = runTest {
        val rig = rig()
        rig.pins.clear()

        rig.kidTv.tap("test")
        advanceUntilIdle()

        assertEquals(KidTvStatus.Failed(KidTvFailure.PlaylistGone(playlist.key)), rig.kidTv.status.value)
        assertTrue(rig.connectCalls.isEmpty())
    }

    @Test
    fun `tapping while it is on stops it, pauses first, then ends the cast`() = runTest {
        val rig = rig()
        rig.kidTv.tap("test")
        advanceUntilIdle()

        rig.kidTv.tap("test again")
        advanceUntilIdle()

        assertEquals(KidTvStatus.Ready, rig.kidTv.status.value)
        assertFalse(rig.lock.modeOn.value)
        assertEquals(1, rig.pauses)
        assertEquals(1, rig.endedCasts)
    }

    @Test
    fun `the cast ending by itself leaves kid mode without ending anything again`() = runTest {
        val rig = rig()
        rig.kidTv.tap("test")
        advanceUntilIdle()

        device.value = null
        advanceUntilIdle()

        assertEquals(KidTvStatus.Ready, rig.kidTv.status.value)
        assertFalse(rig.lock.modeOn.value)
        assertEquals(0, rig.endedCasts)
        assertEquals(0, rig.pauses)
    }

    @Test
    fun `the cast moving to another TV leaves kid mode`() = runTest {
        val rig = rig()
        rig.kidTv.tap("test")
        advanceUntilIdle()

        device.value = "Bedroom TV"
        advanceUntilIdle()

        assertFalse(rig.lock.modeOn.value)
        assertEquals(KidTvStatus.Ready, rig.kidTv.status.value)
    }

    @Test
    fun `choices are stored and survive a restore`() = runTest {
        val rig = rig()

        rig.kidTv.chooseTv("Bedroom TV", "test")
        rig.kidTv.restore(KidTvChoice("playlist:other", "Kitchen TV"))

        assertEquals(KidTvChoice("playlist:other", "Kitchen TV"), rig.store.read())
        assertEquals(KidTvChoice("playlist:other", "Kitchen TV"), rig.kidTv.choice.value)
    }

    @Test
    fun `the choice survives a round trip through its codec`() {
        val choice = KidTvChoice("playlist:PL1", "Living room TV")

        assertEquals(choice, KidTvCodec.decode(KidTvCodec.encode(choice)))
        assertTrue(choice.complete)
        assertFalse(KidTvChoice("playlist:PL1", null).complete)
        assertFalse(KidTvChoice(null, "TV").complete)
    }

    @Test
    fun `kid mode is on for the lock or for the TV and off only when both are off`() {
        val lock = KidLock()

        lock.tvOn("TV", "test")
        assertTrue(lock.modeOn.value)
        lock.lock("test")
        lock.tvOff("test")
        assertTrue("the phone lock keeps it on", lock.modeOn.value)
        lock.unlock("test")
        assertFalse(lock.modeOn.value)
    }
}
