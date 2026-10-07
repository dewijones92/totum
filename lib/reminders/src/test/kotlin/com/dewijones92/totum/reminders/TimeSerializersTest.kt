package com.dewijones92.totum.reminders

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class TimeSerializersTest {

    @Test
    fun `an instant is stored as epoch milliseconds`() {
        val at = Instant.parse("2026-10-07T16:30:00Z")
        val text = Json.encodeToString(InstantSerializer, at)
        assertEquals(at.toEpochMilli().toString(), text)
        assertEquals(at, Json.decodeFromString(InstantSerializer, text))
    }

    @Test
    fun `dates, times and days are stored as readable text`() {
        val date = LocalDate.of(2026, 10, 7)
        assertEquals("\"2026-10-07\"", Json.encodeToString(LocalDateSerializer, date))
        assertEquals(date, Json.decodeFromString(LocalDateSerializer, "\"2026-10-07\""))
        val time = LocalTime.of(17, 30)
        assertEquals("\"17:30\"", Json.encodeToString(LocalTimeSerializer, time))
        assertEquals(time, Json.decodeFromString(LocalTimeSerializer, "\"17:30\""))
        assertEquals("\"TUESDAY\"", Json.encodeToString(DayOfWeekSerializer, DayOfWeek.TUESDAY))
        assertEquals(DayOfWeek.TUESDAY, Json.decodeFromString(DayOfWeekSerializer, "\"TUESDAY\""))
    }
}
