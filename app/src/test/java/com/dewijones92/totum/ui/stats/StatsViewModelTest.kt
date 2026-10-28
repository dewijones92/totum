package com.dewijones92.totum.ui.stats

import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.Period
import com.dewijones92.totum.stats.StatEvent
import com.dewijones92.totum.stats.TopKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-10-14T15:00:00Z")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun listen(at: String, author: String) = StatEvent.Listen(
        at = Instant.parse(at).toEpochMilli(),
        playId = "$author@$at",
        itemId = "$author-song",
        title = "A song",
        author = author,
        kind = ListenKind.MUSIC,
        heardMs = 60_000,
        durationMs = 180_000,
    )

    private val events = mutableListOf(
        listen("2026-10-14T09:00:00Z", "Satie"),
        listen("2026-10-14T10:00:00Z", "Satie"),
        listen("2026-10-01T10:00:00Z", "Chopin"),
    )

    private fun model() = StatsViewModel(
        load = { events },
        breaks = { emptyList() },
        exsurgeOn = { true },
        now = { now },
        zone = { ZoneId.of("Europe/London") },
        computation = dispatcher,
    )

    @Test
    fun `the page opens on the week, and a period chip changes everything on it`() = runTest(dispatcher) {
        val model = model()
        model.reload()
        advanceUntilIdle()

        assertEquals(Period.WEEK, model.page.value?.period)
        assertEquals(2, model.page.value?.totals?.all?.plays)

        model.show(Period.MONTH)
        advanceUntilIdle()
        assertEquals(3, model.page.value?.totals?.all?.plays)
        assertEquals(listOf("Satie", "Chopin"), model.page.value?.top?.get(TopKind.ARTISTS)?.map { it.label })
    }

    @Test
    fun `a bar and a top entry each open the listens behind them`() = runTest(dispatcher) {
        val model = model()
        model.reload()
        advanceUntilIdle()

        model.openBar(model.page.value!!.bars.last())
        assertEquals(2, model.detail.value?.listens?.size)

        model.closeDetail()
        model.show(Period.MONTH)
        advanceUntilIdle()
        model.openTop(TopKind.ARTISTS, model.page.value!!.top.getValue(TopKind.ARTISTS).last())
        assertEquals("Chopin", model.detail.value?.listens?.single()?.author)
    }

    @Test
    fun `opening the page again reads what was recorded since`() = runTest(dispatcher) {
        val model = model()
        model.reload()
        advanceUntilIdle()
        events += listen("2026-10-14T14:00:00Z", "Debussy")

        model.reload()
        advanceUntilIdle()

        assertEquals(3, model.page.value?.totals?.all?.plays)
    }
}
