package com.dewijones92.totum.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ListenStatsTest {

    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-10-14T15:00:00Z")

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    private fun listen(
        at: String,
        heard: Long,
        kind: ListenKind = ListenKind.MUSIC,
        author: String? = "Erik Satie",
        title: String = "Gymnopédie",
        id: String = title,
        album: String? = null,
        source: String? = null,
        duration: Long? = 180_000,
        watched: Boolean = false,
    ) = StatEvent.Listen(
        at = at(at),
        playId = "$id@$at",
        itemId = id,
        title = title,
        author = author,
        source = source,
        album = album,
        kind = kind,
        watched = watched,
        heardMs = heard,
        durationMs = duration,
    )

    @Test
    fun `a listen counts at thirty seconds or half the item, whichever comes first`() {
        assertTrue(listen("2026-10-14T10:00:00Z", 30_000).counts)
        assertFalse(listen("2026-10-14T10:00:00Z", 29_000).counts)
        assertTrue(listen("2026-10-14T10:00:00Z", 10_000, duration = 20_000).counts)
        assertFalse(listen("2026-10-14T10:00:00Z", 9_000, duration = 20_000).counts)
        assertFalse(listen("2026-10-14T10:00:00Z", 9_000, duration = null).counts)
    }

    @Test
    fun `totals split plays and time by kind, and watching from listening`() {
        val listens = listOf(
            listen("2026-10-14T10:00:00Z", 60_000),
            listen("2026-10-14T11:00:00Z", 5_000),
            listen("2026-10-14T12:00:00Z", 1_800_000, kind = ListenKind.PODCAST, duration = 3_600_000),
            listen("2026-10-14T13:00:00Z", 600_000, kind = ListenKind.VIDEO, watched = true),
        )

        val totals = totals(listens)

        assertEquals(Totals(3, 2_465_000), totals.all)
        assertEquals(Totals(1, 65_000), totals.byKind[ListenKind.MUSIC])
        assertEquals(Totals(1, 1_800_000), totals.byKind[ListenKind.PODCAST])
        assertEquals(600_000L, totals.watchedMs)
        assertEquals(1_865_000L, totals.listenedMs)
    }

    @Test
    fun `a period keeps only what it covers`() {
        val listens = listOf(
            listen("2026-10-14T09:00:00Z", 60_000),
            listen("2026-10-10T09:00:00Z", 60_000),
            listen("2026-09-20T09:00:00Z", 60_000),
            listen("2025-12-01T09:00:00Z", 60_000),
        )

        assertEquals(1, listens.within(Period.DAY, now, zone).size)
        assertEquals(2, listens.within(Period.WEEK, now, zone).size)
        assertEquals(3, listens.within(Period.MONTH, now, zone).size)
        assertEquals(4, listens.within(Period.YEAR, now, zone).size)
        assertEquals(4, listens.within(Period.ALL, now, zone).size)
    }

    @Test
    fun `week bars run oldest to today, one per day, and a bar opens its own listens`() {
        val listens = listOf(listen("2026-10-14T09:00:00Z", 60_000), listen("2026-10-12T09:00:00Z", 60_000))

        val bars = bars(listens, Period.WEEK, now, zone)

        assertEquals(7, bars.size)
        assertEquals("2026-10-14", bars.last().key)
        assertEquals(1, bars.last().plays)
        assertEquals(1, bars[4].plays)
        assertEquals(listOf(listens[1]), listens.inBar(bars[4], zone))
    }

    @Test
    fun `day bars are hourly and year bars monthly`() {
        val listens = listOf(listen("2026-10-14T09:30:00Z", 60_000), listen("2026-03-01T09:00:00Z", 60_000))

        val hours = bars(listens, Period.DAY, now, zone)
        assertEquals(24, hours.size)
        assertEquals(1, hours[10].plays)
        assertEquals(listOf(listens[0]), listens.inBar(hours[10], zone))

        val months = bars(listens, Period.YEAR, now, zone)
        assertEquals(12, months.size)
        assertEquals("2026-03", months[4].key)
        assertEquals(listOf(listens[1]), listens.inBar(months[4], zone))
    }

    @Test
    fun `top artists rank by counted plays, then time`() {
        val listens = listOf(
            listen("2026-10-14T09:00:00Z", 60_000, author = "Satie"),
            listen("2026-10-14T09:10:00Z", 60_000, author = "Satie"),
            listen("2026-10-14T09:20:00Z", 60_000, author = "Chopin"),
            listen("2026-10-14T09:30:00Z", 900_000, author = "Debussy"),
            listen("2026-10-14T09:40:00Z", 1_000_000, kind = ListenKind.PODCAST, author = "A show", source = "A show"),
        )

        val artists = top(listens, TopKind.ARTISTS)

        assertEquals(listOf("Satie", "Debussy", "Chopin"), artists.map { it.label })
        assertEquals(2, artists.first().plays)
        assertEquals(listOf("A show"), top(listens, TopKind.SHOWS).map { it.label })
        assertEquals(2, listens.forTop(TopKind.ARTISTS, "Satie").size)
    }

    @Test
    fun `the heatmap adds time by weekday and hour`() {
        val grid = heatmap(listOf(listen("2026-10-14T09:00:00Z", 60_000), listen("2026-10-14T09:20:00Z", 30_000)), zone)

        assertEquals(90_000L, grid[2][10])
        assertEquals(90_000L, grid.sumOf { it.sum() })
    }

    @Test
    fun `streaks count days with a counted listen`() {
        val listens = listOf("10", "11", "12", "14").map { listen("2026-10-${it}T09:00:00Z", 60_000) } +
            listen("2026-10-13T09:00:00Z", 1_000)

        assertEquals(Streaks(current = 1, longest = 3), streaks(listens, LocalDate.of(2026, 10, 14), zone))
        assertEquals(Streaks(0, 0), streaks(emptyList(), LocalDate.of(2026, 10, 14), zone))
    }

    @Test
    fun `events survive the log and a bad line is counted, not fatal`() {
        val event = listen("2026-10-14T09:00:00Z", 60_000)

        val decoded = StatsCodec.read(sequenceOf(StatsCodec.line(event), "{not json", ""))

        assertEquals(listOf<StatEvent>(event), decoded.events)
        assertEquals(1, decoded.unreadable)
    }
}
