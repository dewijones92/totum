package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class DayUsage(
    val day: String,
    val calls: Int,
    val failures: Int,
    val byCaller: Map<String, Int>,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val medianMs: Long,
)

@Serializable
data class UsageSummary(val days: List<DayUsage>, val last: List<UsageRecord>)

internal class UsageLog(private val file: File, private val zone: ZoneId = ZoneId.of("Europe/London")) {

    @Synchronized
    fun record(entry: UsageRecord) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(AskJson.encodeToString(UsageRecord.serializer(), entry) + "\n")
        }.onFailure { Diag.warn("ai", "usage not recorded", it) }
    }

    fun read(): List<UsageRecord> = if (!file.exists()) {
        emptyList()
    } else {
        file.readLines().mapNotNull { line ->
            runCatching { AskJson.decodeFromString(UsageRecord.serializer(), line) }.getOrNull()
        }
    }

    fun summary(days: Int, nowMs: Long): UsageSummary = summarise(read(), days, nowMs, zone)
}

internal fun summarise(records: List<UsageRecord>, days: Int, nowMs: Long, zone: ZoneId): UsageSummary {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val byDay = records.groupBy { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
    val shown = (days - 1 downTo 0).map {
        today.minusDays(
            it.toLong()
        )
    }.map { day -> dayUsage(day, byDay[day].orEmpty()) }
    return UsageSummary(shown, records.sortedByDescending { it.atMs }.take(LAST_SHOWN))
}

private fun dayUsage(day: LocalDate, records: List<UsageRecord>): DayUsage {
    val times = records.map { it.tookMs }.sorted()
    return DayUsage(
        day = day.toString(),
        calls = records.size,
        failures = records.count { !it.ok },
        byCaller = records.groupingBy { it.caller }.eachCount().toSortedMap(),
        inputTokens = records.sumOf { it.inputTokens ?: 0 },
        outputTokens = records.sumOf { it.outputTokens ?: 0 },
        costUsd = records.sumOf { it.costUsd ?: 0.0 },
        medianMs = times.getOrElse(times.size / 2) { 0 },
    )
}

private const val LAST_SHOWN = 20
