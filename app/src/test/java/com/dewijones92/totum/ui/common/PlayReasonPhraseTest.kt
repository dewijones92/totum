package com.dewijones92.totum.ui.common

import com.dewijones92.totum.R
import com.dewijones92.totum.domain.ChosenWay
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayReasonPhraseTest {

    @Test
    fun `no recorded reason says Totum has no record`() {
        val phrase = (null as PlayReason?).phrase()

        assertEquals(R.string.why_unknown, phrase.withDetail)
        assertEquals(R.string.why_unknown, phrase.withoutDetail)
        assertNull(phrase.detail)
    }

    @Test
    fun `every way of choosing an item has its own sentence`() {
        val resources = ChosenWay.entries.associateWith { PlayReason.Chosen(it).phrase().withoutDetail }

        assertEquals(ChosenWay.entries.size, resources.values.toSet().size)
        assertEquals(R.string.why_chosen_tap, resources.getValue(ChosenWay.TAP))
        assertEquals(R.string.why_chosen_peek, resources.getValue(ChosenWay.PEEK))
        assertEquals(R.string.why_chosen_link, resources.getValue(ChosenWay.LINK))
        assertEquals(R.string.why_chosen_pin, resources.getValue(ChosenWay.PIN))
        assertEquals(R.string.why_chosen_shorts, resources.getValue(ChosenWay.SHORTS_REEL))
        assertEquals(R.string.why_chosen_ask, resources.getValue(ChosenWay.ASK_CARD))
        assertEquals(R.string.why_chosen_queue_row, resources.getValue(ChosenWay.QUEUE_ROW))
        assertEquals(R.string.why_chosen_trail, resources.getValue(ChosenWay.BACK_OR_FORWARD))
        assertEquals(R.string.why_chosen_switch, resources.getValue(ChosenWay.SWITCH))
        assertEquals(R.string.why_chosen_shuffle, resources.getValue(ChosenWay.SHUFFLE))
        assertEquals(R.string.why_chosen_notification, resources.getValue(ChosenWay.NOTIFICATION))
    }

    @Test
    fun `a reason with a name uses the sentence that names it and one without uses the plain sentence`() {
        val named = PlayReason.Next("Peep Show").phrase()
        assertEquals(R.string.why_next_group, named.withDetail)
        assertEquals("Peep Show", named.detail)
        assertEquals(R.string.why_next_queue, named.withoutDetail)
        assertNotEquals(named.withDetail, named.withoutDetail)

        assertNull(PlayReason.Next(null).phrase().detail)
        assertEquals("the previous one", R.string.why_previous_group, PlayReason.Previous("G").phrase().withDetail)
        assertEquals(R.string.why_previous_queue, PlayReason.Previous(null).phrase().withoutDetail)
        assertEquals(R.string.why_repeat_group_named, PlayReason.RepeatGroup("G").phrase().withDetail)
        assertEquals(R.string.why_repeat_group, PlayReason.RepeatGroup(null).phrase().withoutDetail)
        assertEquals(R.string.why_explore_named, PlayReason.Explore("Tune").phrase().withDetail)
        assertEquals(R.string.why_explore, PlayReason.Explore(null).phrase().withoutDetail)
        assertEquals(R.string.why_related_after_queue_named, PlayReason.RelatedAfterQueue("Tune").phrase().withDetail)
        assertEquals(R.string.why_related_after_queue, PlayReason.RelatedAfterQueue(null).phrase().withoutDetail)
        assertEquals(R.string.why_radio_named, PlayReason.Radio("Jazz").phrase().withDetail)
        assertEquals(R.string.why_radio, PlayReason.Radio(null).phrase().withoutDetail)
    }

    @Test
    fun `the reasons that carry no name have one sentence either way`() {
        listOf(PlayReason.RepeatOne, PlayReason.RepeatQueue, PlayReason.NewUpload).forEach {
            val phrase = it.phrase()
            assertEquals(phrase.withDetail, phrase.withoutDetail)
            assertNull(phrase.detail)
        }
        assertEquals(R.string.why_repeat_one, PlayReason.RepeatOne.phrase().withoutDetail)
        assertEquals(R.string.why_repeat_queue, PlayReason.RepeatQueue.phrase().withoutDetail)
        assertEquals(R.string.why_new_upload, PlayReason.NewUpload.phrase().withoutDetail)
    }

    @Test
    fun `the report names every reason and spells the detail in brackets when there is one`() {
        assertEquals("chosen-tap", PlayReason.Chosen(ChosenWay.TAP).describe())
        assertEquals("repeat-one", PlayReason.RepeatOne.describe())
        assertEquals("next-in-queue[Peep Show]", PlayReason.Next("Peep Show").describe())
        assertEquals("next-in-queue[none]", PlayReason.Next(null).describe())
        assertEquals("explore[Tune]", PlayReason.Explore("Tune").describe())
        assertEquals("radio[Jazz]", PlayReason.Radio("Jazz").describe())
        assertTrue(ChosenWay.entries.map { PlayReason.Chosen(it).describe() }.toSet().size == ChosenWay.entries.size)
    }
}
