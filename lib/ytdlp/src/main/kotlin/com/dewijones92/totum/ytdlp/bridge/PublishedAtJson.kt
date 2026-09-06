package com.dewijones92.totum.ytdlp.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** `timestamp` is exact; `upload_date` (YYYYMMDD) is the day only, so it lands at midnight UTC. */
internal fun JsonObject.publishedAt(): Instant? {
    val epochSeconds = (this["timestamp"] ?: this["release_timestamp"])?.jsonPrimitive?.doubleOrNull
    if (epochSeconds != null) return Instant.ofEpochSecond(epochSeconds.toLong())
    val day = this["upload_date"]?.jsonPrimitive?.contentOrNullSafe()
        ?.takeIf { it.length == UPLOAD_DATE_LENGTH && it.all(Char::isDigit) }
        ?: return null
    return runCatching {
        LocalDate.parse(day, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant()
    }.getOrNull()
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null

private const val UPLOAD_DATE_LENGTH = 8
