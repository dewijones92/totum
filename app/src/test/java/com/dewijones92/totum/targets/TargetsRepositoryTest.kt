package com.dewijones92.totum.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetTrouble
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.data.targets.TroubleKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.operations.Ended
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class TargetsRepositoryTest {

    private class FakeApi : TargetsApi {
        var answer: HomeAnswer<TargetsAnswer> = HomeAnswer.Ok(TargetsAnswer(emptyList()))
        val queued = mutableListOf<Pair<String, TargetQueueRequest>>()
        val controlled = mutableListOf<Pair<String, TargetControl>>()
        var queueAnswer: HomeAnswer<TargetState>? = null
        var controlAnswer: HomeAnswer<TargetState>? = null
        var reads = 0
        val readAnswers = ArrayDeque<HomeAnswer<TargetsAnswer>>()

        override suspend fun targets(): HomeAnswer<TargetsAnswer> {
            reads++
            return readAnswers.removeFirstOrNull() ?: answer
        }

        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> {
            queued += id to request
            return queueAnswer ?: HomeAnswer.Ok(
                TargetState(
                    Target(id, id, TargetKind.TV),
                    entries = request.items.map { TargetEntry(it) },
                    at = 0,
                    playing = true,
                ),
            )
        }

        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> {
            controlled += id to control
            return controlAnswer ?: HomeAnswer.Ok(TargetState(Target(id, id, TargetKind.TV)))
        }
    }

    private data class Replayed(val items: List<String>, val mode: QueueMode)

    private val api = FakeApi()
    private val colours = InMemoryTargetColours()
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val operations = Operations()
    private val ended = mutableListOf<Ended>()
    private val playedHere = mutableListOf<Replayed>()
    private val playOn = InMemoryPlayOn()
    private var unmetered = true
    private val repository = TargetsRepository(
        api,
        { Requester("me", "My phone") },
        colours,
        scope,
        operations,
        playHere = { items, mode, _, _ -> playedHere += Replayed(items.map { it.item.id.value }, mode) },
        words = FakeWords,
        sleepFade = { fade },
        playOn = playOn,
        unmetered = { unmetered },
    )
    private var fade = 20.seconds

    private object FakeWords : TargetWords {
        val kindsAsked = mutableListOf<TargetKind>()

        override fun trouble(label: String, trouble: TargetTrouble, title: String?, kind: TargetKind): String {
            kindsAsked += kind
            return "${trouble.kind} on $label for ${title ?: "nothing"}: ${trouble.reason}"
        }

        override fun signInAgain() = "SIGN IN AGAIN"
    }

    init {
        scope.launch { operations.ended.collect { ended += it } }
    }

    private val tv = TargetState(Target("lg-tv", "LG TV", TargetKind.TV))
    private val bar = TargetState(Target("bar", "Soundbar"))

    private fun item(id: String) = PlayableItem(
        MediaItem(MediaItemId(id), SourceId("feed"), "Episode $id", publishedAt = null, duration = null),
        PlayHandle.Podcast(audioUrl = HttpUrl.of("https://example.com/$id.mp3")),
    )

    @Test
    fun `what the Pi lists is shown, each target gets its own colour, and the colours are kept`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv, bar)))

        repository.refresh("test")

        assertEquals(listOf("lg-tv", "bar"), repository.targets.value.map { it.target.id })
        val painted = repository.colours.value
        assertNotEquals(painted.getValue("lg-tv"), painted.getValue("bar"))
        assertEquals(TargetColours.PHONE, painted.getValue(TargetColours.PHONE_ID))
        assertEquals(painted, colours.load())
    }

    @Test
    fun `a failed read keeps what was shown and says why`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("first")

        api.answer = HomeAnswer.No("❌ The home server didn't answer")
        repository.refresh("second")

        assertEquals(listOf("lg-tv"), repository.targets.value.map { it.target.id })
        assertEquals("❌ The home server didn't answer", repository.problem.value)
    }

    @Test
    fun `nothing is redirected while the phone is the one being looked at`() = runTest {
        val taken = repository.take(listOf(item("a")), QueueMode.PLAY, null, null)

        assertFalse(taken)
        assertTrue(api.queued.isEmpty())
    }

    @Test
    fun `while a target is selected the items go there, from this phone, as asked`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        repository.select("lg-tv")

        val taken = repository.take(
            listOf(item("a"), item("b")),
            QueueMode.PLAY,
            QueueGroup("shuffle:bluey", "Bluey"),
            MediaItemId("b"),
        )

        assertTrue(taken)
        val (id, request) = api.queued.single()
        assertEquals("lg-tv", id)
        assertEquals(listOf("a", "b"), request.items.map { it.itemId })
        assertEquals(QueueMode.PLAY, request.mode)
        assertEquals("Bluey", request.groupTitle)
        assertEquals("b", request.startItemId)
        assertEquals(Requester("me", "My phone"), request.from)
        assertEquals(item("a").toBackup(), request.items.first())
    }

    @Test
    fun `an edit the Pi refuses because the queue changed reads the queue again`() = runTest {
        api.controlAnswer = HomeAnswer.No("⚠️ The queue changed since you looked; showing it again", 409)
        val before = api.reads

        repository.act("tv", TargetControl(com.dewijones92.totum.data.targets.TargetAction.REMOVE, 2, itemId = "x"))
        scope.advanceUntilIdle()

        assertEquals(before + 1, api.reads)
    }

    @Test
    fun `an edited colour is saved`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")

        repository.recolour("lg-tv", TargetColours.palette[5])

        assertEquals(TargetColours.palette[5], repository.colours.value.getValue("lg-tv"))
        assertEquals(TargetColours.palette[5], colours.load().getValue("lg-tv"))
    }

    @Test
    fun `an answer to a queue request replaces that target's state in the list`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv, bar)))
        repository.refresh("test")

        repository.queue("lg-tv", TargetQueueRequest(listOf(item("a").toBackup()), QueueMode.REPLACE))

        assertTrue(repository.targets.value.first { it.target.id == "lg-tv" }.playing)
        assertFalse(repository.targets.value.first { it.target.id == "bar" }.playing)
    }

    @Test
    fun `a device first met in an answer to a queue request is painted at once, not left in the phone's colour`() =
        runTest {
            api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
            repository.refresh("test")
            assertNull(repository.colours.value["bar"])

            repository.queue("bar", TargetQueueRequest(listOf(item("a").toBackup()), QueueMode.REPLACE))

            val painted = repository.colours.value
            assertTrue("bar has a colour", "bar" in painted)
            assertNotEquals(TargetColours.PHONE, painted.getValue("bar"))
            assertNotEquals(painted.getValue("lg-tv"), painted.getValue("bar"))
            assertEquals(painted, colours.load())
        }

    @Test
    fun `the device last used stays the destination from any screen, leaving the queue changes nothing`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        repository.select("lg-tv")

        assertEquals("lg-tv", repository.selected.value)
        assertTrue(repository.take(listOf(item("a")), QueueMode.PLAY, null, null))
        assertEquals(1, api.queued.size)
    }

    @Test
    fun `the last used device is remembered across a restart`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        repository.select("lg-tv")

        val restarted = TargetsRepository(
            api,
            { Requester("me", "My phone") },
            colours,
            scope,
            operations,
            words = FakeWords,
            playOn = playOn,
            unmetered = { unmetered },
        )
        restarted.refresh("start")

        assertEquals("lg-tv", restarted.selected.value)
    }

    @Test
    fun `a last used device restored from a backup is the one remembered and offered`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")

        repository.restoreLastUsed("lg-tv")

        assertEquals("lg-tv", repository.lastUsed)
        assertEquals("lg-tv", repository.selected.value)
        assertEquals("lg-tv", playOn.load())
    }

    @Test
    fun `away from home a play stays on this phone and the last used device is not offered`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        repository.select("lg-tv")
        unmetered = false
        repository.refresh("on mobile data")

        assertEquals(listOf("phone"), repository.options.value.map { it.id })
        assertNull(repository.selected.value)
        assertFalse(repository.take(listOf(item("a")), QueueMode.PLAY, null, null))
        assertTrue(api.queued.isEmpty())
        assertEquals("lg-tv", playOn.load())
    }

    @Test
    fun `away from home a listed browser is not offered either, and picking it earlier does not send a play to it`() =
        runTest {
            val silk = TargetState(Target("browser-fire1", "Silk", TargetKind.BROWSER))
            api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv, silk)))
            repository.refresh("test")
            repository.select("browser-fire1")
            unmetered = false
            repository.refresh("on mobile data")

            assertEquals(listOf("phone"), repository.options.value.map { it.id })
            assertNull(repository.selected.value)
            assertFalse(repository.take(listOf(item("a")), QueueMode.PLAY, null, null))
            assertTrue(api.queued.isEmpty())
        }

    @Test
    fun `every queue request tells the Pi this phone can read starting`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        repository.select("lg-tv")

        repository.take(listOf(item("a")), QueueMode.PLAY, null, null)
        repository.playNextOn("lg-tv", TargetEntry(item("b").toBackup()))

        assertEquals(listOf(true, true), api.queued.map { it.second.understandsStarting })
    }

    @Test
    fun `back at home the last used device is the default again`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        repository.select("lg-tv")
        unmetered = false
        repository.refresh("on mobile data")
        unmetered = true
        repository.refresh("home Wi-Fi")

        assertEquals("lg-tv", repository.selected.value)
    }

    @Test
    fun `a device the Pi no longer lists is not a destination`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv, bar)))
        repository.refresh("test")
        repository.select("lg-tv")
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(bar)))
        repository.refresh("again")

        assertNull(repository.selected.value)
        assertFalse(repository.take(listOf(item("a")), QueueMode.PLAY, null, null))
    }

    @Test
    fun `devices that are switched off are listed and offered at home`() = runTest {
        val asleep = tv.copy(power = TargetPower.ASLEEP)
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(asleep, bar)))
        repository.refresh("test")

        assertEquals(listOf("phone", "lg-tv", "bar"), repository.options.value.map { it.id })
        assertEquals(TargetPower.ASLEEP, repository.options.value.first { it.id == "lg-tv" }.power)
    }

    @Test
    fun `one failed read does not take the home devices away, two do`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        api.answer = HomeAnswer.No("down")

        repository.refresh("first failure")
        assertEquals(listOf("phone", "lg-tv"), repository.options.value.map { it.id })

        repository.refresh("second failure")
        assertEquals(listOf("phone"), repository.options.value.map { it.id })
    }

    @Test
    fun `when the Pi stops answering after devices were listed the picker says home devices are unreachable`() =
        runTest {
            api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
            repository.refresh("test")
            assertFalse(repository.homeUnreachable.value)
            api.answer = HomeAnswer.No("down")

            repository.refresh("first failure")
            assertFalse("one failed read is not yet an outage", repository.homeUnreachable.value)
            repository.refresh("second failure")

            assertTrue(repository.homeUnreachable.value)
            assertEquals(listOf("phone"), repository.options.value.map { it.id })
        }

    @Test
    fun `the unreachable line goes as soon as the Pi answers again`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        api.answer = HomeAnswer.No("down")
        repository.refresh("one")
        repository.refresh("two")
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))

        repository.refresh("back")

        assertFalse(repository.homeUnreachable.value)
        assertEquals(listOf("phone", "lg-tv"), repository.options.value.map { it.id })
    }

    @Test
    fun `a phone that never had a device and was never told to use one says nothing when the Pi is down`() = runTest {
        api.answer = HomeAnswer.No("down")

        repository.refresh("one")
        repository.refresh("two")

        assertFalse(repository.homeUnreachable.value)
    }

    @Test
    fun `a remembered device with the Pi down at launch is reported as unreachable`() = runTest {
        playOn.save("lg-tv")
        val fresh = TargetsRepository(
            api,
            { Requester("me", "My phone") },
            InMemoryTargetColours(),
            scope,
            playOn = playOn,
        )
        api.answer = HomeAnswer.No("down")

        fresh.refresh("one")
        fresh.refresh("two")

        assertTrue(fresh.homeUnreachable.value)
    }

    @Test
    fun `on mobile data with the Pi answering is not reported as unreachable, that is just away`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        unmetered = false

        repository.refresh("on mobile data")

        assertFalse(repository.homeUnreachable.value)
    }

    @Test
    fun `picking wakes a switched-off device only, and the phone clears the choice`() =
        runTest {
            val asleep = tv.copy(power = TargetPower.ASLEEP)
            api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(asleep, bar)))
            repository.refresh("test")

            repository.choose(repository.options.value.first { it.id == "bar" })
            assertTrue(api.controlled.isEmpty())
            assertEquals("bar", playOn.load())

            repository.choose(repository.options.value.first { it.id == "lg-tv" })
            assertEquals(listOf("lg-tv" to TargetAction.WAKE), api.controlled.map { it.first to it.second.action })
            assertEquals("lg-tv", playOn.load())

            repository.choose(repository.options.value.first { it.isPhone })
            assertNull(repository.selected.value)
            assertNull(playOn.load())
        }

    @Test
    fun `the address to type into a browser is offered at home, and not away, and gone when the Pi stops giving it`() =
        runTest {
            api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv), browserAddress = "192.168.0.5:8771"))
            repository.refresh("test")
            assertEquals("192.168.0.5:8771", repository.browserAddress.value)

            unmetered = false
            repository.refresh("on mobile data")
            assertNull(repository.browserAddress.value)

            unmetered = true
            repository.refresh("back on Wi-Fi")
            assertEquals("192.168.0.5:8771", repository.browserAddress.value)

            api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
            repository.refresh("an older Pi")
            assertNull(repository.browserAddress.value)
        }

    @Test
    fun `a browser the Pi lists is a device like any other, with a colour of its own`() = runTest {
        val silk = TargetState(Target("browser-fire1", "Silk", TargetKind.BROWSER))
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv, silk)))
        repository.refresh("test")

        val offered = repository.options.value.first { it.id == "browser-fire1" }
        repository.choose(offered)

        assertEquals("browser-fire1", repository.selected.value)
        assertNotEquals(TargetColours.PHONE, repository.colours.value.getValue("browser-fire1"))
        assertNotEquals(repository.colours.value.getValue("lg-tv"), repository.colours.value.getValue("browser-fire1"))
        assertTrue("a browser is not woken", api.controlled.isEmpty())
    }

    @Test
    fun `a device switched on by hand is offered as such, and picking it while off wakes nothing`() = runTest {
        val mic = TargetState(
            Target("mic", "Soundmic"),
            power = TargetPower.ASLEEP,
            switchedOnByHand = true,
        )
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv.copy(power = TargetPower.ASLEEP), mic)))
        repository.refresh("test")

        val offered = repository.options.value.first { it.id == "mic" }
        repository.choose(offered)

        assertTrue(offered.switchedOnByHand)
        assertFalse(repository.options.value.first { it.id == "lg-tv" }.switchedOnByHand)
        assertTrue("no wake is sent for a speaker that comes on by hand", api.controlled.isEmpty())
        assertEquals("mic", repository.selected.value)
        assertEquals("mic", playOn.load())
    }

    @Test
    fun `an item playing on a device is marked with that device`() = runTest {
        val playing = bar.copy(entries = listOf(TargetEntry(item("a").toBackup())), at = 0, playing = true)
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv, playing)))
        repository.refresh("test")

        assertEquals(mapOf("a" to bar.target), repository.playingOn.value)
    }

    @Test
    fun `what is reported is what the Pi really queued, not what was asked for`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        api.queueAnswer = HomeAnswer.Ok(
            TargetState(tv.target, entries = listOf(TargetEntry(item("a").toBackup())), at = 0, playing = true),
        )

        val end = repository.redirect(
            Redirected("lg-tv", "LG TV", listOf(item("a"), item("b")), QueueMode.PLAY, null, null)
        )

        assertEquals(OperationEnd.Done(1), end)
        assertTrue(playedHere.isEmpty())
    }

    @Test
    fun `a Pi that could not start it leaves the items to this phone, with a notice that says so`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        repository.select("lg-tv")
        api.queueAnswer = HomeAnswer.Ok(
            TargetState(
                tv.target,
                entries = listOf(TargetEntry(item("a").toBackup())),
                at = 0,
                problem = "⚠️ Couldn't reach LG TV",
            ),
        )

        repository.take(listOf(item("a"), item("b")), QueueMode.PLAY, null, MediaItemId("a"))

        assertEquals(listOf(Replayed(listOf("a", "b"), QueueMode.PLAY)), playedHere)
        val end = ended.single().end as OperationEnd.Failed
        assertTrue(end.why, end.why.startsWith("⚠️") && end.why.contains("LG TV") && end.why.contains("this phone"))
    }

    @Test
    fun `a Pi that refuses the items leaves them to this phone as well`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        repository.select("lg-tv")
        api.queueAnswer = HomeAnswer.No("❌ Nothing in that was playable on LG TV")

        repository.take(listOf(item("a")), QueueMode.END, null, null)

        assertEquals(listOf(Replayed(listOf("a"), QueueMode.END)), playedHere)
        assertTrue((ended.single().end as OperationEnd.Failed).why.contains("Nothing in that was playable"))
    }

    @Test
    fun `a Pi that took it keeps it, and nothing is played here`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        repository.select("lg-tv")

        repository.take(listOf(item("a"), item("b")), QueueMode.PLAY, null, null)

        assertTrue(playedHere.isEmpty())
        assertEquals(OperationEnd.Done(2), ended.single().end)
    }

    @Test
    fun `a target the Pi says is waking shows on the progress strip until it is awake`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv.copy(power = TargetPower.WAKING))))

        repository.refresh("test")

        assertEquals(
            listOf(OperationKind.WAKE_TARGET to "LG TV"),
            operations.running.value.map { it.kind to it.subject },
        )

        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        scope.advanceUntilIdle()

        assertTrue(operations.running.value.isEmpty())
        val end = ended.single { it.kind == OperationKind.WAKE_TARGET }
        assertEquals(OperationEnd.Done(1), end.end)
    }

    @Test
    fun `a target that keeps waking over several reads is on the strip once`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv.copy(power = TargetPower.WAKING))))

        repository.refresh("one")
        repository.refresh("two")
        repository.refresh("three")

        assertEquals(1, operations.running.value.count { it.kind == OperationKind.WAKE_TARGET })
    }

    @Test
    fun `a TV that did not wake ends the strip with the reason the Pi gave`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv.copy(power = TargetPower.WAKING))))
        repository.refresh("test")

        val failed = tv.copy(
            power = TargetPower.ASLEEP,
            problem = "❌ LG TV didn't wake: check 'Turn on via Wi-Fi' in its settings",
        )
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(failed)))
        repository.refresh("test")
        scope.advanceUntilIdle()

        val end = ended.single { it.kind == OperationKind.WAKE_TARGET }.end
        assertEquals(OperationEnd.Failed("LG TV didn't wake: check 'Turn on via Wi-Fi' in its settings"), end)
    }

    @Test
    fun `a wake the phone stops hearing about does not sit on the strip for ever`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv.copy(power = TargetPower.WAKING))))
        repository.refresh("test")

        scope.advanceUntilIdle()

        assertTrue(operations.running.value.isEmpty())
        assertTrue(ended.single { it.kind == OperationKind.WAKE_TARGET }.end is OperationEnd.Failed)
    }

    private fun accepted(vararg ids: String) = TargetState(
        tv.target,
        entries = ids.map { TargetEntry(item(it).toBackup()) },
        at = 0,
        starting = true,
    )

    private fun started(playing: Boolean, trouble: TargetTrouble? = null, vararg ids: String) = HomeAnswer.Ok(
        TargetsAnswer(
            listOf(
                tv.copy(
                    entries = ids.map { TargetEntry(item(it).toBackup()) },
                    at = 0,
                    playing = playing,
                    trouble = trouble,
                    problem = trouble?.kind?.name,
                ),
            ),
        ),
    )

    private fun playOn(vararg ids: String) =
        Redirected("lg-tv", "LG TV", ids.map { item(it) }, QueueMode.PLAY, null, null)

    @Test
    fun `a Pi that says it is starting is watched, and when it plays nothing is played here`() = runTest {
        api.queueAnswer = HomeAnswer.Ok(accepted("a", "b"))
        api.readAnswers += HomeAnswer.Ok(TargetsAnswer(listOf(accepted("a", "b"))))
        api.readAnswers += started(true, null, "a", "b")

        val end = repository.redirect(playOn("a", "b"))

        assertEquals(OperationEnd.Done(2), end)
        assertTrue(playedHere.isEmpty())
        assertTrue("nothing was taken off the Pi", api.controlled.isEmpty())
        assertEquals(2, api.reads)
    }

    @Test
    fun `a Pi that finishes starting without playing gets the items played here, and taken off it`() = runTest {
        api.queueAnswer = HomeAnswer.Ok(accepted("a", "b"))
        api.readAnswers += started(false, TargetTrouble(TroubleKind.NOT_CONNECTED), "a", "b")

        val end = repository.redirect(playOn("a", "b"))

        assertEquals(listOf(Replayed(listOf("a", "b"), QueueMode.PLAY)), playedHere)
        val why = (end as OperationEnd.Failed).why
        assertTrue(why, why.contains("NOT_CONNECTED on LG TV"))
        val (id, control) = api.controlled.single()
        assertEquals("lg-tv", id)
        assertEquals(TargetAction.REMOVE, control.action)
        assertEquals(listOf("a", "b"), control.itemIds)
    }

    @Test
    fun `a Pi whose start the phone loses track of is not left starting for ever`() = runTest {
        api.queueAnswer = HomeAnswer.Ok(accepted("a"))
        api.answer = HomeAnswer.No("❌ The home server didn't answer")

        val end = repository.redirect(playOn("a"))

        assertEquals(listOf(Replayed(listOf("a"), QueueMode.PLAY)), playedHere)
        assertTrue(end is OperationEnd.Failed)
        assertEquals(TargetAction.REMOVE, api.controlled.single().second.action)
    }

    @Test
    fun `an answer that never came is checked against the Pi before the phone plays it as well`() = runTest {
        api.queueAnswer = HomeAnswer.No("❌ The home server didn't answer")
        api.answer = started(true, null, "a")

        val end = repository.redirect(playOn("a"))

        assertEquals(OperationEnd.Done(1), end)
        assertTrue("the Pi had it, so the phone did not", playedHere.isEmpty())
        assertTrue(api.controlled.isEmpty())
    }

    @Test
    fun `an answer that never came, with the items not on the Pi, is played here and nothing is taken off`() = runTest {
        api.queueAnswer = HomeAnswer.No("❌ The home server didn't answer")
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))

        repository.redirect(playOn("a"))

        assertEquals(listOf(Replayed(listOf("a"), QueueMode.PLAY)), playedHere)
        assertTrue(api.controlled.isEmpty())
    }

    @Test
    fun `an answer that never came and a Pi that cannot be read is played here and taken off`() = runTest {
        api.queueAnswer = HomeAnswer.No("❌ The home server didn't answer")
        api.answer = HomeAnswer.No("❌ The home server didn't answer")

        repository.redirect(playOn("a"))

        assertEquals(listOf(Replayed(listOf("a"), QueueMode.PLAY)), playedHere)
        assertEquals(TargetAction.REMOVE, api.controlled.single().second.action)
    }

    @Test
    fun `a clear refusal is played here with nothing to take off`() = runTest {
        api.queueAnswer = HomeAnswer.No("❌ Nothing in that was playable on LG TV", 422)

        repository.redirect(playOn("a"))

        assertEquals(listOf(Replayed(listOf("a"), QueueMode.PLAY)), playedHere)
        assertTrue(api.controlled.isEmpty())
        assertEquals(0, api.reads)
    }

    @Test
    fun `the Pi's problem is composed from its kind, its item and its reason, with the phone's own words`() = runTest {
        val state = tv.copy(
            entries = listOf(TargetEntry(item("a").toBackup())),
            trouble = TargetTrouble(TroubleKind.COULD_NOT_PLAY, "a", "it is not on this Pi"),
            problem = "❌ plain Pi text",
        )

        assertEquals("COULD_NOT_PLAY on LG TV for Episode a: it is not on this Pi", repository.troubleLine(state))
        assertEquals("plain", repository.troubleLine(tv.copy(problem = "plain")))
        assertNull(repository.troubleLine(tv))
    }

    @Test
    fun `the words for a problem are asked for the kind of device, so a browser is closed, not switched off`() =
        runTest {
            val silk = TargetState(
                Target("browser-fire1", "Silk", TargetKind.BROWSER),
                trouble = TargetTrouble(TroubleKind.SWITCHED_OFF),
            )

            FakeWords.kindsAsked.clear()
            repository.troubleLine(silk)

            assertEquals(listOf(TargetKind.BROWSER), FakeWords.kindsAsked)
        }

    @Test
    fun `a 401 says to sign in again, wherever it came from`() = runTest {
        api.controlAnswer = HomeAnswer.No("❌ The home server said no (HTTP 401)", 401)

        repository.act("lg-tv", TargetControl(TargetAction.NEXT))
        scope.advanceUntilIdle()

        assertEquals("SIGN IN AGAIN", repository.problem.value)

        api.answer = HomeAnswer.No("❌ The home server said no (HTTP 401)", 401)
        repository.refresh("test")
        assertEquals("SIGN IN AGAIN", repository.problem.value)
    }

    @Test
    fun `an action that failed stays on screen through the next successful read`() = runTest {
        api.controlAnswer = HomeAnswer.No("❌ The home server didn't answer")
        repository.act("lg-tv", TargetControl(TargetAction.NEXT))
        scope.advanceUntilIdle()
        assertEquals("❌ The home server didn't answer", repository.problem.value)

        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("a read that worked")

        assertEquals("❌ The home server didn't answer", repository.problem.value)

        api.controlAnswer = null
        repository.act("lg-tv", TargetControl(TargetAction.NEXT))
        scope.advanceUntilIdle()
        assertNull(repository.problem.value)
    }

    @Test
    fun `every control the phone sends is logged with what it was`() = runTest {
        val lines = mutableListOf<String>()
        val before = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "targets") lines += message }
        try {
            repository.act("lg-tv", TargetControl(TargetAction.SEEK, positionMs = 42_000))
            repository.act("lg-tv", TargetControl(TargetAction.REMOVE, index = 3, itemId = "x"))
            scope.advanceUntilIdle()
        } finally {
            Diag.sink = before
        }

        assertTrue(
            lines.toString(),
            lines.any { it.startsWith("sending to lg-tv: SEEK") && it.contains("positionMs=42000") }
        )
        assertTrue(lines.toString(), lines.any { it.startsWith("sending to lg-tv: REMOVE index=3 itemId=x") })
        assertTrue(lines.toString(), lines.any { it.startsWith("lg-tv answered SEEK") })
    }

    @Test
    fun `a target that is asleep is not on the strip, only waking is`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv.copy(power = TargetPower.ASLEEP))))

        repository.refresh("test")

        assertTrue(operations.running.value.isEmpty())
    }

    @Test
    fun `a sleep timer for a target goes to the Pi with the fade from the setting`() = runTest {
        repository.startSleep("lg-tv", 30.minutes)
        fade = 0.seconds
        repository.startSleep("lg-tv", 5.minutes)

        val (id, first) = api.controlled.first()
        assertEquals("lg-tv", id)
        assertEquals(TargetAction.SLEEP, first.action)
        assertEquals(SleepKind.AFTER_TIME, first.sleep!!.kind)
        assertEquals(1_800, first.sleep!!.seconds)
        assertEquals(20, first.sleep!!.fadeSeconds)
        assertEquals(
            "a fade of none is sent as none, not defaulted",
            0,
            api.controlled.last().second.sleep!!.fadeSeconds
        )
    }

    @Test
    fun `stopping after items names them and the title, and cancelling sends the off kind`() = runTest {
        repository.sleepAfterItems("bar", listOf("t1", "t2"), "Guitar Recital")
        repository.cancelSleep("bar")

        val ask = api.controlled.first().second.sleep!!
        assertEquals(SleepKind.AFTER_ITEMS, ask.kind)
        assertEquals(listOf("t1", "t2"), ask.itemIds)
        assertEquals("Guitar Recital", ask.title)
        assertEquals(SleepKind.OFF, api.controlled.last().second.sleep!!.kind)
    }
}
