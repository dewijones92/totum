package com.dewijones92.totum.groups

import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class PublishDaysTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a failed lookup is asked again, and an answer is kept`() = runTest {
        val answers = ArrayDeque(
            listOf(PublishDays.Answer.Failed("HTTP 503"), PublishDays.Answer.Day(LocalDate.of(2026, 10, 31))),
        )
        var asked = 0
        val days = PublishDays(folder.newFile("days.json")) {
            asked++
            answers.removeFirst()
        }
        val id = MediaItemId("abcdefghijk")

        assertEquals(null, days.dayOf(id))
        assertEquals(LocalDate.of(2026, 10, 31), days.dayOf(id))
        assertEquals(LocalDate.of(2026, 10, 31), days.dayOf(id))
        assertEquals(2, asked)
    }

    @Test
    fun `a page with no date is remembered as having none`() = runTest {
        var asked = 0
        val days = PublishDays(folder.newFile("days.json")) {
            asked++
            PublishDays.Answer.NoDay
        }
        val id = MediaItemId("abcdefghijk")

        days.dayOf(id)
        days.dayOf(id)

        assertEquals(1, asked)
    }
}
