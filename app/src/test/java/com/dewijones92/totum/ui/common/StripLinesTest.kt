package com.dewijones92.totum.ui.common

import com.dewijones92.totum.operations.Operation
import com.dewijones92.totum.operations.OperationKind
import org.junit.Assert.assertEquals
import org.junit.Test

class StripLinesTest {

    private fun op(id: Long, kind: OperationKind, step: Int? = null) =
        Operation(id, "k$id", kind, "item $id", step = step, steps = step?.let { 100 })

    @Test
    fun `two or more jobs of one kind fold into one line, a single job keeps its own`() {
        val running = listOf(
            op(1, OperationKind.HOME_DOWNLOAD, 10),
            op(2, OperationKind.ASK),
            op(3, OperationKind.HOME_DOWNLOAD, 30),
            op(4, OperationKind.HOME_DOWNLOAD, 50),
        )
        val lines = stripLines(running)
        assertEquals(2, lines.size)
        val group = lines[0] as StripLine.Group
        assertEquals(OperationKind.HOME_DOWNLOAD, group.kind)
        assertEquals(listOf(1L, 3L, 4L), group.operations.map { it.id })
        assertEquals(30, group.percent)
        assertEquals(2L, (lines[1] as StripLine.Single).operation.id)
    }

    @Test
    fun `a group with no progress has no percent`() {
        val group = stripLines(listOf(op(1, OperationKind.PREPARE_TORRENT), op(2, OperationKind.PREPARE_TORRENT)))
            .single() as StripLine.Group
        assertEquals(null, group.percent)
    }
}
