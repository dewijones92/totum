package com.dewijones92.totum.ui.queue

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.queue.queueGroupHeaderKey
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class QueueLayoutTest {

    private fun entry(id: String, group: String? = null) = QueueEntry(
        PlayableItem(
            MediaItem(MediaItemId(id), SourceId("s"), id, publishedAt = null, duration = null),
            PlayHandle.Podcast(),
        ),
        group?.let { QueueGroup(it, it) },
    )

    @Test
    fun `a split group draws a header per run, a collapsed group one header and no rows`() {
        val queue = listOf(entry("a"), entry("b1", "B"), entry("b2", "B"), entry("c"), entry("b3", "B"))
        assertEquals(
            listOf(
                QueueRowSpec.UngroupedHeading(0, 1, "ungrouped-0"),
                QueueRowSpec.Entry(0, "a"),
                QueueRowSpec.GroupHeading(1, QueueGroup("B", "B"), true, 3, queueGroupHeaderKey("B")),
                QueueRowSpec.Entry(1, "b1"),
                QueueRowSpec.Entry(2, "b2"),
                QueueRowSpec.UngroupedHeading(3, 1, "ungrouped-3"),
                QueueRowSpec.Entry(3, "c"),
                QueueRowSpec.GroupHeading(4, QueueGroup("B", "B"), false, 3, "group-4-B"),
                QueueRowSpec.Entry(4, "b3"),
            ),
            queueRows(queue, emptySet(), dragging = false),
        )
        assertEquals(
            listOf("ungrouped-0", "a", queueGroupHeaderKey("B"), "ungrouped-3", "c"),
            queueRows(queue, setOf("B"), dragging = false).map { it.key },
        )
    }

    @Test
    fun `the playing item is never hidden by collapsing its group, and its run keeps a header`() {
        val queue = listOf(entry("a"), entry("b1", "B"), entry("b2", "B"), entry("c"), entry("b3", "B"))
        assertEquals(
            listOf("ungrouped-0", "a", queueGroupHeaderKey("B"), "ungrouped-3", "c", "group-4-B", "b3"),
            queueRows(queue, setOf("B"), dragging = false, playing = 4).map { it.key },
        )
        assertEquals(
            listOf("ungrouped-0", "a", queueGroupHeaderKey("B"), "b1", "ungrouped-3", "c"),
            queueRows(queue, setOf("B"), dragging = false, playing = 1).map { it.key },
        )
    }

    @Test
    fun `a queue with no groups has no headings at all`() {
        val queue = (1..5).map { entry("e$it") }
        assertEquals((1..5).map { "e$it" }, queueRows(queue, emptySet(), dragging = false).map { it.key })
    }

    @Test
    fun `it lays out random queues exactly as the per-row scan it replaced`() {
        val random = Random(42)
        repeat(300) { round ->
            val size = random.nextInt(0, 60)
            val queue = List(size) { i ->
                entry("r$round-$i", listOf(null, "A", "B", "C").random(random))
            }
            val collapsed = listOf("A", "B", "C").filter { random.nextBoolean() }.toSet()
            val dragging = random.nextBoolean()
            assertEquals(oldLayout(queue, collapsed, dragging), queueRows(queue, collapsed, dragging))
        }
    }

    @Test
    fun `two thousand entries in forty groups lay out in one pass`() {
        val queue = List(2_000) { i -> entry("big$i", if (i % 7 == 0) null else "G${i / 50}") }
        val rows = queueRows(queue, emptySet(), dragging = false)
        assertEquals(2_000, rows.count { it is QueueRowSpec.Entry })
        assertEquals(oldLayout(queue, emptySet(), false), rows)
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private fun oldLayout(entries: List<QueueEntry>, collapsed: Set<String>, dragging: Boolean): List<QueueRowSpec> {
        val rows = mutableListOf<QueueRowSpec>()
        val hasGroups = entries.any { it.group != null }
        entries.forEachIndexed { index, entry ->
            val group = entry.group
            val startsRun = group != null && entries.getOrNull(index - 1)?.group?.id != group.id
            val isFirstRun = group != null && entries.take(index).none { it.group?.id == group.id }
            val alreadyCollapsedAbove = group != null && group.id in collapsed && !isFirstRun
            val splitHeaderMidDrag = !isFirstRun && dragging
            if (startsRun && !alreadyCollapsedAbove && !splitHeaderMidDrag) {
                val run = entries.filter { it.group?.id == group!!.id }
                val key = if (isFirstRun) queueGroupHeaderKey(group!!.id) else "group-$index-${group!!.id}"
                rows += QueueRowSpec.GroupHeading(index, group, isFirstRun, run.size, key)
            }
            if (hasGroups && entry.group == null && (index == 0 || entries[index - 1].group != null)) {
                val run = entries.drop(index).takeWhile { it.group == null }.size
                rows += QueueRowSpec.UngroupedHeading(index, run, "ungrouped-$index")
            }
            if (group != null && group.id in collapsed) return@forEachIndexed
            rows += QueueRowSpec.Entry(index, entry.item.item.id.value)
        }
        return rows
    }
}
