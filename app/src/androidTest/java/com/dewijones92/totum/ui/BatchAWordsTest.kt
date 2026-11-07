package com.dewijones92.totum.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.domain.ChosenWay
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.jobs.tidyDetail
import com.dewijones92.totum.live.liveTagText
import com.dewijones92.totum.queue.QueueNotice
import com.dewijones92.totum.queue.QueueTidy
import com.dewijones92.totum.ui.common.noticeMessage
import com.dewijones92.totum.ui.common.phrase
import com.dewijones92.totum.ui.common.shareListMessage
import com.dewijones92.totum.video.LiveTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BatchAWordsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun playable(title: String) = PlayableItem(
        MediaItem(MediaItemId(title), SourceId("s"), title, publishedAt = null, duration = null),
        PlayHandle.Podcast(),
    )

    @Test
    fun `an already queued item says whether it is playing, next, or where it is`() {
        val item = playable("Zog")

        assertEquals(
            "▶️ “Zog” is already playing",
            context.noticeMessage(QueueNotice.AlreadyQueued(item, 1, true, false))
        )
        assertEquals("⏭️ “Zog” is already next", context.noticeMessage(QueueNotice.AlreadyQueued(item, 2, false, true)))
        assertEquals(
            "📌 “Zog” is already queued at #7. Move it up?",
            context.noticeMessage(QueueNotice.AlreadyQueued(item, 7, false, false)),
        )
    }

    @Test
    fun `a bulk add says how many were already queued, in the singular and the plural`() {
        assertEquals("📌 1 item was already queued", context.noticeMessage(QueueNotice.SomeAlreadyQueued(1)))
        assertEquals("📌 4 items were already queued", context.noticeMessage(QueueNotice.SomeAlreadyQueued(4)))
    }

    @Test
    fun `a shared list is a title then numbered lines`() {
        val message = context.resources.shareListMessage("Peep Show", listOf(playable("A").item, playable("B").item))

        assertEquals("Peep Show\n1. A\n2. B", message)
    }

    @Test
    fun `a shared list stops at a hundred and says how many more`() {
        val items = (1..101).map { playable("T$it").item }
        val lines = context.resources.shareListMessage("Big", items).lines()

        assertEquals("Big", lines.first())
        assertEquals("100. T100", lines[100])
        assertEquals("➕ and 1 more", lines.last())
        assertEquals(102, lines.size)

        val plural = context.resources.shareListMessage("Bigger", (1..130).map { playable("T$it").item }).lines().last()
        assertEquals("➕ and 30 more", plural)
    }

    @Test
    fun `a list of exactly a hundred has no more line, and the title can be shielded`() {
        val items = (1..100).map { playable("T$it").item }

        assertFalse(context.resources.shareListMessage("Exact", items).contains("more"))
        assertTrue(context.resources.shareListMessage("S", items.take(2)) { "hidden" }.endsWith("2. hidden"))
    }

    @Test
    fun `every reason is a sentence that names its group or source when it has one`() {
        fun sentence(reason: PlayReason?): String {
            val phrase = reason.phrase()
            val detail = phrase.detail
            val res = if (detail != null) phrase.withDetail else phrase.withoutDetail
            return detail?.let { context.getString(res, it) } ?: context.getString(res)
        }

        assertEquals("▶️ You tapped it.", sentence(PlayReason.Chosen(ChosenWay.TAP)))
        assertEquals("⏭️ Next in the Peep Show group.", sentence(PlayReason.Next("Peep Show")))
        assertEquals("⏭️ Next in your queue.", sentence(PlayReason.Next(null)))
        assertEquals(
            "⏮️ You went back to the one before it in the Peep Show group.",
            sentence(PlayReason.Previous("Peep Show"))
        )
        assertEquals("🔂 Repeat one is on, so it played again.", sentence(PlayReason.RepeatOne))
        assertEquals(
            "🔁 Repeat group is on, so the Peep Show group started again.",
            sentence(PlayReason.RepeatGroup("Peep Show"))
        )
        assertEquals("🧭 Explore picked it, related to Zog.", sentence(PlayReason.Explore("Zog")))
        assertEquals("🧭 Explore picked it, related to what was playing.", sentence(PlayReason.Explore(null)))
        assertEquals("📻 Radio · Jazz picked it.", sentence(PlayReason.Radio("Radio · Jazz")))
        assertEquals(
            "📥 It is a new upload from something you follow, played from your New group.",
            sentence(PlayReason.NewUpload)
        )
        assertTrue(sentence(null).startsWith("❔ Totum has no record"))
    }

    @Test
    fun `no two reasons share a sentence`() {
        val sentences = ChosenWay.entries.map { way ->
            context.getString(PlayReason.Chosen(way).phrase().withoutDetail)
        }

        assertEquals(sentences.size, sentences.toSet().size)
    }

    @Test
    fun `the live tag reads live, was live, starts soon, or starts at a time`() {
        assertEquals("🔴 LIVE", context.liveTagText(LiveTag.Live))
        assertEquals("⏺️ WAS LIVE", context.liveTagText(LiveTag.WasLive))
        assertEquals("🔴 STARTS SOON", context.liveTagText(LiveTag.StartsSoon(null)))
        val timed = context.liveTagText(LiveTag.StartsSoon(Instant.now().plusSeconds(3_600)))
        assertTrue(timed, timed.startsWith("🔴 STARTS ") && timed != "🔴 STARTS SOON")
    }

    @Test
    fun `the tidy line says what moved, what was cleared, both, or nothing`() {
        assertEquals("12 played items left the queue", tidyDetail(context, QueueTidy.Result(12, 0)))
        assertEquals("1 played item left the queue", tidyDetail(context, QueueTidy.Result(1, 0)))
        assertEquals("3 failed downloads cleared", tidyDetail(context, QueueTidy.Result(0, 3)))
        assertEquals(
            "12 played items left the queue, 3 failed downloads cleared",
            tidyDetail(context, QueueTidy.Result(12, 3)),
        )
        assertEquals("nothing to tidy", tidyDetail(context, QueueTidy.Result(0, 0)))
    }
}
