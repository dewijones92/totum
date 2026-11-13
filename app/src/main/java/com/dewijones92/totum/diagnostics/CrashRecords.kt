package com.dewijones92.totum.diagnostics

import android.content.Context
import com.dewijones92.totum.common.Diag
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

@Serializable
internal data class CrashRecord(
    val atEpochMs: Long,
    val thread: String,
    val exception: String,
    val message: String,
    val stackTrace: String,
    val reportName: String? = null,
)

internal class CrashRecords(private val file: File) {

    fun write(record: CrashRecord) {
        file.parentFile?.mkdirs()
        file.writeText(JSON.encodeToString(CrashRecord.serializer(), record))
    }

    fun pending(): CrashRecord? {
        if (!file.isFile) return null
        return runCatching { JSON.decodeFromString(CrashRecord.serializer(), file.readText()) }
            .onFailure { Diag.warn("diagnostics", "the last-crash record is unreadable, so no crash screen", it) }
            .getOrNull()
    }

    fun clear() {
        file.delete()
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun of(context: Context): CrashRecords = CrashRecords(File(context.filesDir, "last-crash.json"))

        fun recordOf(error: Throwable, thread: String, reportName: String?, atEpochMs: Long): CrashRecord =
            CrashRecord(
                atEpochMs = atEpochMs,
                thread = thread,
                exception = error.javaClass.name,
                message = error.message.orEmpty(),
                stackTrace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString(),
                reportName = reportName,
            )

        fun offer(record: CrashRecord?, underInstrumentation: Boolean): Boolean =
            record != null && !underInstrumentation

        fun noteFor(record: CrashRecord): String =
            "Totum crashed: ${record.exception}${record.message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
    }
}
