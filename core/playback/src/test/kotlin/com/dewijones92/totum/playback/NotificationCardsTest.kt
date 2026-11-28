package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCardsTest {

    private fun choose(
        deviceShown: Boolean = true,
        devicePlaying: Boolean = false,
        phoneHasItem: Boolean = false,
        phonePlaying: Boolean = false,
    ) = NotificationCards.choose(CardInputs(deviceShown, devicePlaying, phoneHasItem, phonePlaying))

    @Test
    fun `with no device shown the phone is the card, whatever it is doing`() {
        assertEquals(Card.PHONE, choose(deviceShown = false, phoneHasItem = true, phonePlaying = true).card)
        assertEquals(Card.PHONE, choose(deviceShown = false, phoneHasItem = false).card)
    }

    @Test
    fun `a shown device with a phone that holds nothing is the card`() {
        assertEquals(Card.DEVICE, choose(devicePlaying = true).card)
        assertEquals(Card.DEVICE, choose(devicePlaying = false).card)
    }

    @Test
    fun `a playing phone beside a device that is not playing keeps the card`() {
        val choice = choose(phoneHasItem = true, phonePlaying = true, devicePlaying = false)

        assertEquals(Card.PHONE, choice.card)
        assertTrue(choice.why, "phone is playing" in choice.why)
    }

    @Test
    fun `when both play the device that was chosen wins`() {
        assertEquals(Card.DEVICE, choose(phoneHasItem = true, phonePlaying = true, devicePlaying = true).card)
    }

    @Test
    fun `when neither plays the chosen device wins over a paused phone item`() {
        assertEquals(Card.DEVICE, choose(phoneHasItem = true).card)
    }

    @Test
    fun `a paused phone item never outranks a playing device`() {
        assertEquals(Card.DEVICE, choose(phoneHasItem = true, devicePlaying = true).card)
    }

    @Test
    fun `exactly one card is shown for every combination of inputs`() {
        val everyCombination = (0 until COMBINATIONS).map { bits ->
            CardInputs(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, bits and 8 != 0)
        }

        everyCombination.forEach { inputs ->
            val choice = NotificationCards.choose(inputs)
            val other = if (choice.card == Card.PHONE) Card.DEVICE else Card.PHONE
            assertTrue("$inputs", choice.shows(choice.card) && !choice.shows(other) && choice.why.isNotBlank())
        }
        assertEquals(COMBINATIONS, everyCombination.toSet().size)
    }

    @Test
    fun `the session id says which card a session is, and each card has its own notification`() {
        assertEquals(Card.DEVICE, NotificationCards.cardOf(RemoteSessionController.REMOTE_SESSION_ID))
        assertEquals(Card.PHONE, NotificationCards.cardOf(""))
        assertNotEquals(
            NotificationCards.notificationIdOf(Card.PHONE),
            NotificationCards.notificationIdOf(Card.DEVICE),
        )
    }

    private companion object {
        const val COMBINATIONS = 16
    }
}
