package com.dewijones92.totum.ui.ask

import com.dewijones92.totum.data.ask.SavedChat
import com.dewijones92.totum.data.ask.SavedTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class ChatChipLabelTest {
    private fun chat(id: String, at: String, request: String) =
        SavedChat(id, Instant.parse(at).toEpochMilli(), listOf(SavedTurn(request)))

    @Test
    fun `a chip names the chat and when it happened`() {
        val label = chatChipLabel(chat("a", "2026-11-07T06:58:00Z", "a Gruffalo film"), ZoneOffset.UTC, Locale.UK)

        assertEquals("a Gruffalo film · 7 Nov, 06:58", label)
    }

    @Test
    fun `two chats that began with the same words get different chips`() {
        val first = chatChipLabel(chat("a", "2026-11-07T06:58:00Z", "radio station"), ZoneOffset.UTC, Locale.UK)
        val second = chatChipLabel(chat("b", "2026-11-06T21:10:00Z", "radio station"), ZoneOffset.UTC, Locale.UK)

        assertNotEquals(first, second)
    }
}
