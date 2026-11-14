package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Diag
import java.io.File

internal data class Receipt(val reportId: String?, val stateAttempts: Int)

internal class ReportReceipt(report: File) {
    private val marker = File(report.parentFile, report.name + SUFFIX)

    fun read(): Receipt? {
        if (!marker.exists()) return null
        val lines = runCatching { marker.readLines() }.getOrElse { error ->
            Diag.warn("diagnostics", "receipt ${marker.name} could not be read", error)
            emptyList()
        }
        val attempts = lines.getOrNull(1)?.toIntOrNull()
        if (attempts == null) {
            Diag.log("diagnostics", "receipt ${marker.name} is unreadable; treating the report as accepted with no id")
            return Receipt(null, 0)
        }
        return Receipt(lines.getOrNull(0)?.takeIf { it.matches(REPORT_ID) }, attempts)
    }

    fun write(receipt: Receipt) {
        marker.writeText("${receipt.reportId.orEmpty()}\n${receipt.stateAttempts}\n")
    }

    fun delete() {
        marker.delete()
    }

    private companion object {
        const val SUFFIX = ".receipt"
        val REPORT_ID = Regex("\\d{8}T\\d{6}-[0-9a-f]{8}")
    }
}
