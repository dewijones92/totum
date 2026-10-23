package com.dewijones92.totum.ui.common

import com.dewijones92.totum.operations.Operation
import com.dewijones92.totum.operations.OperationKind
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationsShownTest {

    private fun op(id: Long, startedAtMs: Long) = Operation(
        id,
        "k$id",
        OperationKind.OPEN_PAGE,
        "x",
        startedAtMs = startedAtMs
    )

    @Test
    fun `work that finishes inside a second never reaches the strip`() {
        val all = listOf(op(1, startedAtMs = 10_000), op(2, startedAtMs = 10_800))

        assertEquals(listOf(1L), shownAt(all, nowMs = 11_000).map { it.id })
        assertEquals(listOf(1L, 2L), shownAt(all, nowMs = 11_800).map { it.id })
    }
}
