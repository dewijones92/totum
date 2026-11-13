package com.dewijones92.totum.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
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

@OptIn(ExperimentalCoroutinesApi::class)
class TargetsRepositoryTest {

    private class FakeApi : TargetsApi {
        var answer: HomeAnswer<TargetsAnswer> = HomeAnswer.Ok(TargetsAnswer(emptyList()))
        val queued = mutableListOf<Pair<String, TargetQueueRequest>>()
        val controlled = mutableListOf<Pair<String, TargetControl>>()
        var queueAnswer: HomeAnswer<TargetState>? = null
        var controlAnswer: HomeAnswer<TargetState>? = null
        var reads = 0

        override suspend fun targets(): HomeAnswer<TargetsAnswer> {
            reads++
            return answer
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
    private val repository = TargetsRepository(
        api,
        { Requester("me", "My phone") },
        colours,
        scope,
        operations,
        playHere = { items, mode, _, _ -> playedHere += Replayed(items.map { it.item.id.value }, mode) },
    )

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
    fun `looking at a target only lasts while the queue is on screen`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv)))
        repository.refresh("test")
        repository.select("lg-tv")

        repository.queueTabLeft()

        assertNull(repository.selected.value)
        assertFalse(repository.take(listOf(item("a")), QueueMode.PLAY, null, null))
        assertTrue(api.queued.isEmpty())
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

    @Test
    fun `a target that is asleep is not on the strip, only waking is`() = runTest {
        api.answer = HomeAnswer.Ok(TargetsAnswer(listOf(tv.copy(power = TargetPower.ASLEEP))))

        repository.refresh("test")

        assertTrue(operations.running.value.isEmpty())
    }
}
