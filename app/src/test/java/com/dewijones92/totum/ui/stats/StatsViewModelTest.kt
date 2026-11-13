package com.dewijones92.totum.ui.stats

import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.Period
import com.dewijones92.totum.stats.StatEvent
import com.dewijones92.totum.stats.TopKind
import com.dewijones92.totum.stats.UnlockNudgeTotals
import com.dewijones92.totum.stats.UnlockNudgeWhat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val events = mutableListOf<StatEvent>(
        listen("2026-10-14T09:00:00Z", "Satie"),
        listen("2026-10-14T10:00:00Z", "Satie"),
        listen("2026-10-01T10:00:00Z", "Chopin"),
    )

    private fun model(access: Boolean = true, label: (String) -> String = { it }) = StatsViewModel(
        load = { events },
        breaks = { emptyList() },
        exsurgeOn = { true },
        now = { now },
        zone = { ZoneId.of("Europe/London") },
        computation = dispatcher,
        phoneAccess = { access },
        screenOnNow = { false },
        appLabel = label,
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

    private fun phoneEvents() = listOf(
        StatEvent.PhoneUnlock(Instant.parse("2026-10-14T08:00:00Z").toEpochMilli(), "com.whatsapp"),
        StatEvent.PhoneUnlock(Instant.parse("2026-10-14T08:05:00Z").toEpochMilli(), "com.whatsapp"),
        StatEvent.PhoneUnlock(Instant.parse("2026-10-14T13:00:00Z").toEpochMilli(), "com.android.chrome"),
        StatEvent.PhoneUnlock(Instant.parse("2026-10-10T13:00:00Z").toEpochMilli(), "com.android.chrome"),
        StatEvent.PhoneUnlock(Instant.parse("2026-09-20T13:00:00Z").toEpochMilli(), "com.android.chrome"),
        StatEvent.PhoneScreen(Instant.parse("2026-10-14T08:00:00Z").toEpochMilli(), true),
        StatEvent.PhoneScreen(Instant.parse("2026-10-14T08:10:00Z").toEpochMilli(), false),
        StatEvent.UnlockNudge(Instant.parse("2026-10-14T08:05:00Z").toEpochMilli(), UnlockNudgeWhat.SHOWN, 5, 2),
        StatEvent.UnlockNudge(Instant.parse("2026-10-14T08:05:09Z").toEpochMilli(), UnlockNudgeWhat.CONTINUED),
    )

    @Test
    fun `the phone section reads today and a 7 or 30 day trend from the same log`() = runTest(dispatcher) {
        events += phoneEvents()
        val model = model()
        model.reload()
        advanceUntilIdle()

        val phone = model.page.value!!.phone
        assertEquals(3, phone.today.unlocks)
        assertEquals(10 * 60_000L, phone.today.screenMs)
        assertEquals(7, phone.trend.bars.size)
        assertEquals(4, phone.trend.unlocks)
        assertEquals(UnlockNudgeTotals(1, 1, 0, 0), phone.nudges)
        assertEquals(listOf("com.whatsapp", "com.android.chrome"), phone.firstApps.map { it.key })

        model.showPhoneTrend(30)
        advanceUntilIdle()
        assertEquals(30, model.page.value!!.phone.trend.bars.size)
        assertEquals(5, model.page.value!!.phone.trend.unlocks)
    }

    @Test
    fun `an hour bar and a day bar and a first app each open the unlocks behind them, labelled with the app's name`() =
        runTest(dispatcher) {
            events += phoneEvents()
            val model = model(label = { "label:$it" })
            model.reload()
            advanceUntilIdle()

            model.openPhoneBar(model.page.value!!.phone.today.perHour[9])
            assertEquals(2, model.detail.value?.unlocks?.size)
            model.closeDetail()

            model.openPhoneBar(model.page.value!!.phone.trend.bars.last())
            assertEquals(3, model.detail.value?.unlocks?.size)
            model.closeDetail()

            model.openPhoneApp(model.page.value!!.phone.firstApps.first())
            assertEquals("label:com.whatsapp", model.detail.value?.title)
            assertEquals(2, model.detail.value?.unlocks?.size)
        }

    @Test
    fun `without usage access the page says so and still has the log it already holds`() = runTest(dispatcher) {
        events += phoneEvents()
        val model = model(access = false)
        model.reload()
        advanceUntilIdle()

        assertFalse(model.page.value!!.phone.accessGranted)
        assertEquals(3, model.page.value!!.phone.today.unlocks)
    }
}
