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
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TargetsRepositoryTest {

    private class FakeApi : TargetsApi {
        var answer: HomeAnswer<TargetsAnswer> = HomeAnswer.Ok(TargetsAnswer(emptyList()))
        val queued = mutableListOf<Pair<String, TargetQueueRequest>>()
        val controlled = mutableListOf<Pair<String, TargetControl>>()
        var queueAnswer: HomeAnswer<TargetState>? = null

        override suspend fun targets() = answer

        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> {
            queued += id to request
            return queueAnswer ?: HomeAnswer.Ok(TargetState(Target(id, id, TargetKind.TV), playing = true))
        }

        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> {
            controlled += id to control
            return HomeAnswer.Ok(TargetState(Target(id, id, TargetKind.TV)))
        }
    }

    private val api = FakeApi()
    private val colours = InMemoryTargetColours()
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val repository = TargetsRepository(
        api,
        { Requester("me", "My phone", owner = true) },
        colours,
        scope,
        Operations(),
    )

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
        assertEquals(Requester("me", "My phone", owner = true), request.from)
        assertEquals(item("a").toBackup(), request.items.first())
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
}
