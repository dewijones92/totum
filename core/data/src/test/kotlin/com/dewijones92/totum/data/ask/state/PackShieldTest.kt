package com.dewijones92.totum.data.ask.state

import com.dewijones92.totum.common.Breadcrumbs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackShieldTest {

    private val hidden = "🙈 Title hidden"

    private fun pack(): String = AppStateBuilder.build(
        StateTestData.inputs(
            events = listOf(
                Breadcrumbs.Entry(StateTestData.NOW - 1_000, "stats", "listen began: Day 2 highlights as VIDEO", 1),
                Breadcrumbs.Entry(StateTestData.NOW - 900, "queue", "queued Peep Show S02E03 Mugging", 2),
            ),
            listens = listOf(
                ListenFact(StateTestData.NOW - 5_000, "Day 2 highlights", "A Channel", null, null, "video", true, 1, 2, true, "v1"),
            ),
            titleFor = { id, title, _ -> if (id == "v1") hidden else title },
        ),
    ).json.toString()

    @Test
    fun `a shielded title is not in the pack in any row, listen or event`() {
        val text = pack()

        assertFalse(text, text.contains("Day 2 highlights"))
        assertTrue(text, text.contains(hidden))
    }

    @Test
    fun `an unshielded title is untouched`() {
        assertTrue(pack().contains("Peep Show S02E03 Mugging"))
    }
}
